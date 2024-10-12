package world.startoy.polling.usecase;

import com.querydsl.core.Tuple;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.startoy.polling.adapter.repository.PollOptionRepository;
import world.startoy.polling.adapter.repository.VoteRepository;
import world.startoy.polling.config.CloudFrontConfig;
import world.startoy.polling.domain.Poll;
import world.startoy.polling.domain.PollOption;
import world.startoy.polling.domain.Vote;
import world.startoy.polling.usecase.dto.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;


@RequiredArgsConstructor
@Service
public class VoteService {

    private final VoteRepository voteRepository;
    private final PollOptionRepository pollOptionRepository; // PollOption 데이터를 가져오기 위한 Repository
    private final PollService pollService;
    private final CloudFrontConfig cloudFrontConfig;


    // 투표
    @Transactional
    public Vote submitVote(Long pollId, Long optionId, String voterIp) {
        if (hasVoted(pollId, voterIp)) {
            throw new IllegalStateException("이미 투표하셨습니다.");
        }

        String voteUid = UUID.randomUUID().toString();
        Vote vote = new Vote();
        vote.setId(1L);
        vote.setVoteUid(voteUid);
        vote.setPollId(pollId);
        vote.setOptionId(optionId);
        vote.setVoterIp(voterIp);
        vote.setCreatedAt(LocalDateTime.now());

        return voteRepository.save(vote);
    }

    // 투표 여부 확인
    public boolean hasVoted(Long pollId, String voterIp) {
        return !voteRepository.findByPollIdAndVoterIp(pollId, voterIp).isEmpty();
    }


    // 투표(Vote) 저장 후 투표 정보 반환
    public VoteCreateResponse createVote(VoteCreateRequest request, String voterIp) {

        Poll poll = pollService.findByPollUid(request.getPollUid());

        if(poll == null){
            throw new IllegalStateException("존재하지 않는 pollUid입니다.");
        }

        if (hasVoted(poll.getId(), voterIp)) {
            throw new IllegalStateException("이미 투표하셨습니다.");
        }

        PollOption selectedOption = pollOptionRepository.findByPollOptionUid(request.getSelectedPollOptionUid())
                .orElseThrow(() -> new IllegalArgumentException("Invalid poll option UID"));

        String voteUid = UUID.randomUUID().toString();
        Vote vote = Vote.builder()
                .voteUid(voteUid)
                .pollId(poll.getId())
                .optionId(selectedOption.getId())
                .voterIp(voterIp)
                .createdAt(LocalDateTime.now())
                .build();
        voteRepository.save(vote);

        // 옵션 정보 및 각 옵션의 득표수 가져오기
        List<PollOptionResponse> pollOptions = getVoteCountByPollId(poll.getId());

        return VoteCreateResponse.builder()
                .voteUid(vote.getVoteUid())
                .pollUid(poll.getPollUid())
                .votedPollOptionUid(selectedOption.getPollOptionUid())
                .pollOptions(pollOptions)
                .build();
    }

    // 투표율 조회
    public List<OptionVoteRateDTO> getVoteRates(Long pollId) {
        // 특정 pollId에 대한 모든 투표 가져오기
        List<Vote> votes = voteRepository.findByPollId(pollId);

        // 전체 투표 수 계산
        long totalVotes = votes.size();

        // 각 optionId별 투표 수 집계
        /*
         * Java Stream API 를 이용 Vote 엔티티 리스트를 optionId를 기준으로 그룹화하고
         * 각 그룹의 투표 수를 계산하여 Map 형태로 반환
         * */
        Map<Long, Long> votesPerOption = votes.stream()
                .collect(Collectors.groupingBy(Vote::getOptionId, Collectors.counting()));

        // 각 optionId에 해당하는 OptionVoteRate 생성
        return votesPerOption.entrySet().stream()
                .map(entry -> {
                    // PollOption 데이터에서 UID와 텍스트 가져오기
                    Long optionId = entry.getKey(); // 백엔드에서 사용되는 PollOption의 ID
                    var pollOption = pollOptionRepository.findById(optionId)
                            .orElseThrow(() -> new IllegalStateException("옵션 ID를 찾을 수 없습니다: " + optionId));

                    return new OptionVoteRateDTO(
                            pollOption.getPollOptionUid(),                // API 응답에 사용될 PollOptionUid
                            pollOption.getPollOptionText(),               // optionText
                            entry.getValue(),                             // votes
                            totalVotes == 0 ? 0 : (entry.getValue() * 100.0 / totalVotes) // voteRate
                    );
                })
                .collect(Collectors.toList());
    }

    // pollId로 옵션 정보 및 옵션별 득표수 가져오기
    public List<PollOptionResponse> getVoteCountByPollId(Long pollId) {
        String cloudFrontUrl = cloudFrontConfig.getCloudfrontUrl();
        List<PollOptionResponse> results =  voteRepository.findPollOptionsWithVoteCount(pollId, cloudFrontUrl);
        return results;
    }

    // 투표 삭제
    @Transactional
    public void deleteVote(Long pollId, String voterIp) {
        // 주어진 pollId와 voterIp에 해당하는 투표를 조회
        List<Vote> votes = voteRepository.findByPollIdAndVoterIp(pollId, voterIp);

        // 해당 투표가 존재하는 경우 삭제
        if (!votes.isEmpty()) {
            for (Vote vote : votes) {
                voteRepository.delete(vote);
            }
        } else {
            throw new IllegalArgumentException("해당 투표를 찾을 수 없습니다."); // 예외 처리
        }
    }

}
