package com.studyagent.controller;

import com.studyagent.common.Result;
import com.studyagent.model.vo.UserInfoVO;
import com.studyagent.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受保护接口示例。
 *
 * <p>存在的意义有两个：一是给前端路由守卫一个"校验登录态"的探针；
 * 二是第 5~6 天验收"带 token 访问受保护接口"这条链路时有一个稳定的靶子。</p>
 */
@RestController
@RequestMapping("/user")
@Tag(name = "02-用户", description = "用户信息")
public class UserController {

    @GetMapping("/profile")
    @Operation(summary = "获取当前用户信息（需要登录）",
            description = "未带 token 会返回 HTTP 401 + 统一返回体，用于前端路由守卫判定登录态",
            security = @SecurityRequirement(name = "bearerAuth"))
    public Result<UserInfoVO> profile() {
        // 这里故意只回显 token 里的信息，不走库：既能证明鉴权链路通了，
        // 又能验证"token 里的 userId 与库里一致"。
        var loginUser = UserContext.current();
        UserInfoVO vo = UserInfoVO.builder()
                .id(loginUser.getUserId())
                .username(loginUser.getUsername())
                .nickname(loginUser.getNickname())
                .build();
        return Result.ok(vo);
    }
}
