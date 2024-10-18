package world.startoy.polling.usecase;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.startoy.polling.adapter.repository.FileStorageRepository;
import world.startoy.polling.adapter.repository.PollOptionRepository;
import world.startoy.polling.adapter.repository.PollRepository;
import world.startoy.polling.adapter.repository.PostRepository;
import world.startoy.polling.config.CloudFrontConfig;
import world.startoy.polling.domain.FileStorage;
import world.startoy.polling.domain.Poll;
import world.startoy.polling.domain.PollOption;
import world.startoy.polling.domain.Post;
import world.startoy.polling.usecase.dto.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@Log4j2
public class PostService {

    private final PostRepository postRepository;
    private final PollRepository pollRepository;
    private final PollOptionRepository pollOptionRepository;
    private final PollService pollService;
    private final VoteService voteService;
    private final FileStorageRepository fileStorageRepository;
    private final CloudFrontConfig cloudFrontConfig;


    @PersistenceContext
    private EntityManager entityManager;


    // 모든 게시글 조회 (메인페이지 보여질 부분)
    public PostListResponse getPostListResponse() {
        String cloudFrontUrl = cloudFrontConfig.getCloudfrontUrl();
        List<PostDTO> postDTOList = postRepository.findAllPostWithFile(cloudFrontUrl);

        return new PostListResponse(postDTOList);
    }


    // 게시글 상세 조회 + 옵션별 득표수 + 사용자 투표 옵션(사용자 투표 여부)
    public Optional<PostDetailResponse> findPostDetailResponseByPostUid(String postUid, String voterIp) {
        // Native SQL Query 작성
        String sql =
                "SELECT " +
                        "pp.post_uid, pp.title, ppf.file_name, pp.created_at, pp.created_by, " +
                        "pp2.poll_uid, pp2.poll_seq, pp2.poll_category, pp2.poll_description, " +
                        "ppo.poll_option_uid, ppo.poll_option_seq, ppo.poll_option_text, pof.file_name, " +
                        "COUNT(pv.voter_ip) AS votedCount, " +
                        "(SELECT count(vote_id) FROM pl_vote WHERE option_id = ppo.poll_option_id AND voter_ip = ?2) AS hasVoted, " +
                        "ROW_NUMBER() OVER (ORDER BY pp2.poll_seq) AS row_num " +
                        "FROM pl_post pp " +
                        "LEFT JOIN pl_file_storage ppf ON pp.file_id = ppf.file_id " +
                        "LEFT JOIN pl_poll pp2 ON pp.post_id = pp2.post_id " +
                        "LEFT JOIN pl_poll_option ppo ON pp2.poll_id = ppo.poll_id " +
                        "LEFT JOIN pl_file_storage pof ON ppo.file_id = pof.file_id " +
                        "LEFT JOIN pl_vote pv ON pv.poll_id = pp2.poll_id AND pv.option_id = ppo.poll_option_id " +
                        "WHERE pp.post_uid = ?1 " +
                        "GROUP BY pp.post_uid, pp.title, ppf.file_name, pp.created_at, pp.created_by, " +
                        "pp2.poll_uid, pp2.poll_seq, pp2.poll_category, pp2.poll_description, " +
                        "ppo.poll_option_uid, ppo.poll_option_seq, ppo.poll_option_text, pof.file_name " +
                        "ORDER BY row_num";

        // Native SQL Query 실행
        List<Object[]> result = entityManager.createNativeQuery(sql)
                .setParameter(1, postUid) // ?1
                .setParameter(2, voterIp) // ?2
                .getResultList();

        if (result.isEmpty()) return Optional.empty();

        return convertToPostDetailResponse(result);
    }

    private Optional<PostDetailResponse> convertToPostDetailResponse(List<Object[]> result) {

        // Post 정보는 첫 번째 결과 행에서 가져옵니다.
        Object[] firstRow = result.get(0);

        // Post 정보 추출
        String postUid = (String) firstRow[0];
        String title = (String) firstRow[1];
        String imageUrl = cloudFrontConfig.getCloudfrontUrl((String) firstRow[2]);
        LocalDateTime createdAt = (firstRow[3] instanceof Timestamp) ? ((Timestamp) firstRow[3]).toLocalDateTime() : (LocalDateTime) firstRow[3];
        String createdBy = (String) firstRow[4];

        // PollOptionResponse 리스트로 변환
        List<PollDetailResponse> pollDetails = result.stream()
                .collect(Collectors.groupingBy(row -> new PollDetailKey(
                        (String) row[5], // pollUid
                        (Integer) row[6], // pollSeq
                        (String) row[7], // pollCategory
                        (String) row[8]  // pollDescription
                )))
                .entrySet().stream()
                .map(entry -> {
                    PollDetailKey pollDetailKey = entry.getKey();
                    List<Object[]> pollOptionRows = entry.getValue();

                    List<PollOptionResponse> pollOptions = pollOptionRows.stream()
                            .map(optionRow -> PollOptionResponse.builder()
                                    .pollOptionUid((String) optionRow[9])
                                    .pollOptionSeq((Integer) optionRow[10])
                                    .pollOptionText((String) optionRow[11])
                                    .imageUrl(cloudFrontConfig.getCloudfrontUrl((String) optionRow[12]))
                                    .votedCount((Long) optionRow[13])
                                    .hasVoted(((Number) optionRow[14]).intValue() > 0) // 투표 여부 확인
                                    .build())
                            .sorted(Comparator.comparingInt(PollOptionResponse::getPollOptionSeq))
                            .collect(Collectors.toList());

                    return PollDetailResponse.builder()
                            .pollUid(pollDetailKey.pollUid)
                            .pollSeq(pollDetailKey.pollSeq)
                            .pollCategory(pollDetailKey.pollCategory)
                            .pollDescription(pollDetailKey.pollDescription)
                            .pollOptions(pollOptions)
                            .build();
                })
                .sorted(Comparator.comparingInt(PollDetailResponse::getPollSeq))
                .collect(Collectors.toCollection(LinkedList::new));

        // PostDetailResponse 생성
        return Optional.ofNullable(PostDetailResponse.builder()
                .postUid(postUid)
                .title(title)
                .imageUrl(imageUrl)
                .createdAt(createdAt)
                .createdBy(createdBy)
                .polls(pollDetails) // Poll 리스트 포함
                .build());
    }

    // PollDetailResponse의 grouping key로 사용할 클래스
    private static class PollDetailKey {
        private final String pollUid;
        private final int pollSeq;
        private final String pollCategory;
        private final String pollDescription;

        public PollDetailKey(String pollUid, int pollSeq, String pollCategory, String pollDescription) {
            this.pollUid = pollUid;
            this.pollSeq = pollSeq;
            this.pollCategory = pollCategory;
            this.pollDescription = pollDescription;
        }

        // equals와 hashCode를 구현해야 grouping이 제대로 작동합니다.
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PollDetailKey that = (PollDetailKey) o;
            return pollSeq == that.pollSeq &&
                    Objects.equals(pollUid, that.pollUid) &&
                    Objects.equals(pollCategory, that.pollCategory) &&
                    Objects.equals(pollDescription, that.pollDescription);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pollUid, pollSeq, pollCategory, pollDescription);
        }
    }


    @Transactional
    public PostCreateResponse createPost(PostCreateRequest request, String createdBy) {
        String newPostUid = UUID.randomUUID().toString();

        FileStorage postFile = FileStorage.builder()
                .fileUid(request.getFileUid())
                .fileName(request.getFileName())
                .isDeleted(false) // 기본값 설정
                .createdAt(LocalDateTime.now())
                .createdBy(createdBy)
                .fileLinkedUid(newPostUid)
                .uploadableType("Post")
                .build();

        FileStorage savedPostFile = fileStorageRepository.save(postFile);

        Post post = Post.builder()
                .postUid(newPostUid)
                .title(request.getTitle())
                .createdAt(LocalDateTime.now())
                .createdBy(createdBy)
                .isDeleted(false) // 기본값 설정
                .file(savedPostFile)
                .build();

        Post savedPost = postRepository.save(post);

        for (PollCreateRequest pollReq : request.getPolls()) {
            String newPollUid = UUID.randomUUID().toString();
            Poll poll = Poll.builder()
                    .pollUid(newPollUid)
                    .pollCategory(pollReq.getPollCategory())
                    .pollDescription(pollReq.getPollDescription())
                    .pollSeq(pollReq.getPollSeq())
                    .post(savedPost) // 관계 설정
                    .isDeleted(false) // 기본값 설정
                    .createdAt(LocalDateTime.now())
                    .createdBy(createdBy)
                    .build();

            Poll savedPoll = pollRepository.save(poll);

            for (PollOptionCreateRequest optionReq : pollReq.getPollOptions()) {
                String newPollOptionUid = UUID.randomUUID().toString();

                FileStorage pollOptionFile = FileStorage.builder()
                        .fileUid(optionReq.getFileUid())
                        .fileName(optionReq.getFileName())
                        .isDeleted(false) // 기본값 설정
                        .createdAt(LocalDateTime.now())
                        .createdBy(createdBy)
                        .fileLinkedUid(newPostUid)
                        .uploadableType("PollOption")
                        .build();

                FileStorage savedPollOptionFile = fileStorageRepository.save(pollOptionFile);

                PollOption pollOption = PollOption.builder()
                        .pollOptionUid(newPollOptionUid)
                        .poll(savedPoll) // 관계 설정
                        .pollOptionText(optionReq.getPollOptionText())
                        .pollOptionSeq(optionReq.getPollOptionSeq())
                        .isDeleted(false) // 기본값 설정
                        .createdAt(LocalDateTime.now())
                        .createdBy(createdBy)
                        .file(savedPollOptionFile)
                        .build();

                pollOptionRepository.save(pollOption);
            }
        }

        return PostCreateResponse.builder()
                .postUid(savedPost.getPostUid())
                .build();
    }
}