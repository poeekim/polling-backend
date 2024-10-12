package world.startoy.polling.adapter.repository;

import com.querydsl.core.Tuple;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import world.startoy.polling.domain.Vote;
import world.startoy.polling.usecase.dto.PollOptionResponse;

import java.util.List;
import java.util.Optional;

@Repository
public interface VoteRepository extends JpaRepository<Vote, Long> {
    // 특정 투표에 특정 ip 사용자 투표 정보 조회
    List<Vote> findByPollIdAndVoterIp(Long pollId, String voterIp);

    List<Vote> findByPollId(Long pollId);

    // 투표 옵션 및 득표수 가져오는 쿼리
    @Query("SELECT new world.startoy.polling.usecase.dto.PollOptionResponse(" +
            "o.pollOptionUid, o.pollOptionSeq, o.pollOptionText, COUNT(v.id), " +
            "CASE WHEN f.fileUid IS NULL THEN '' ELSE CONCAT(:cloudFrontUrl, '/', f.fileName) END) " +
            "FROM PollOption o " +
            "LEFT JOIN Vote v ON o.id = v.optionId " +
            "LEFT JOIN FileStorage f ON o.file.id = f.id " +
            "WHERE o.poll.id = :pollId " +
            "GROUP BY o.pollOptionUid, o.pollOptionText, o.pollOptionSeq, f.fileUid, f.fileName")
    List<PollOptionResponse> findPollOptionsWithVoteCount(@Param("pollId") Long pollId, @Param("cloudFrontUrl") String cloudFrontUrl);

    Optional<Vote> findByVoteUid(String voteUid);
}

