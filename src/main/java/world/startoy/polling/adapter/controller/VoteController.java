package world.startoy.polling.adapter.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import world.startoy.polling.usecase.UserService;
import world.startoy.polling.usecase.VoteService;
import world.startoy.polling.usecase.dto.VoteCancelRequest;
import world.startoy.polling.usecase.dto.VoteCreateRequest;
import world.startoy.polling.usecase.dto.VoteCreateResponse;


@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/vote")
@Tag(name = "Vote", description = "Vote 투표 행위 기록 API")
public class VoteController {

    private final VoteService voteService;
    private final UserService userService;


    @PostMapping
    public VoteCreateResponse createVote(@RequestBody VoteCreateRequest request, HttpServletRequest httpRequest) {
        String voterIp = userService.getClientIp(httpRequest);
        return voteService.createVote(request, voterIp);
    }

    @DeleteMapping
    public ResponseEntity<String> deleteVote(@Valid @RequestBody VoteCancelRequest request, HttpServletRequest httpRequest) {
        String voterIp = userService.getClientIp(httpRequest); // 클라이언트 IP 가져오기
        request.setVoterIp(voterIp); // 요청에 IP 설정

        voteService.deleteVote(request.getPollUid(), request.getSelectedPollOptionUid(), request.getVoterIp());
        return ResponseEntity.ok("투표 취소가 완료됐습니다.");
    }
}

