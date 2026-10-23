package org.example;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.common.ApiResponse;
import org.example.common.ResultCode;
import org.example.entity.WxUser;
import org.example.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信端用户接口。
 * 所有 /user/** 请求须携带请求头 X-Api-Key；除 /user/report 外还须携带 X-Open-Id。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 首次进入上报用户信息。
     * body: { "openId": "...", "nickname": "...", "avatarUrl": "..." }
     * openId 也可以放在请求头 X-Open-Id；重复上报只更新非空资料。
     */
    @PostMapping("/report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> report(
            @RequestHeader(value = "X-Open-Id", required = false) String headerOpenId,
            @RequestBody(required = false) Map<String, Object> body) {

        Map<String, Object> data = body == null ? Map.of() : body;
        String openId = firstText(data.get("openId"), data.get("openid"), headerOpenId);
        if (openId == null) {
            return fail(ResultCode.BAD_REQUEST, "openId 不能为空");
        }
        if (openId.length() > 64) {
            return fail(ResultCode.BAD_REQUEST, "openId 长度不能超过 64");
        }
        String nickname = firstText(data.get("nickname"), data.get("nickName"));
        if (nickname != null && nickname.length() > 64) {
            return fail(ResultCode.BAD_REQUEST, "nickname 长度不能超过 64");
        }
        String avatarUrl = firstText(data.get("avatarUrl"));
        if (avatarUrl != null && avatarUrl.length() > 512) {
            return fail(ResultCode.BAD_REQUEST, "avatarUrl 长度不能超过 512");
        }

        UserService.ReportResult result = userService.report(openId, nickname, avatarUrl);
        WxUser user = result.user();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("userId", user.getId());
        resp.put("openId", user.getOpenid());
        resp.put("created", result.created());
        return ApiResponse.ok(resp).toEntity();
    }

    /**
     * 收藏增删（最终状态语义，非事件增量）。
     * body: { "questionId": "q1024", "action": "add", "ts": 1729580000000 }
     */
    @PostMapping("/favorite")
    public ResponseEntity<ApiResponse<Map<String, Object>>> favorite(
            @RequestHeader(value = "X-Open-Id", required = false) String openId,
            @RequestBody(required = false) Map<String, Object> body) {

        if (!hasText(openId)) {
            return fail(ResultCode.BAD_REQUEST, "缺少请求头 X-Open-Id");
        }

        Map<String, Object> data = body == null ? Map.of() : body;
        String questionId = firstText(data.get("questionId"));
        if (questionId == null) {
            return fail(ResultCode.BAD_REQUEST, "questionId 不能为空");
        }
        if (questionId.length() > 64) {
            return fail(ResultCode.BAD_REQUEST, "questionId 长度不能超过 64");
        }

        String action = firstText(data.get("action"));
        if (!"add".equalsIgnoreCase(action) && !"remove".equalsIgnoreCase(action)) {
            return fail(ResultCode.BAD_REQUEST, "action 必须是 add 或 remove");
        }

        Long ts = parseTs(data.get("ts"));
        if (data.get("ts") != null && ts == null) {
            return fail(ResultCode.BAD_REQUEST, "ts 必须是毫秒时间戳");
        }
        if (ts != null && ts < 0) {
            return fail(ResultCode.BAD_REQUEST, "ts 不能为负数");
        }

        WxUser user = userService.findByOpenId(openId);
        if (user == null) {
            return fail(ResultCode.NOT_FOUND, "用户不存在，请先调用 /user/report");
        }

        long finalTs = ts == null ? System.currentTimeMillis() : ts;
        boolean favorited = userService.setFavorite(
                user.getId(), questionId, action.toLowerCase(), finalTs);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("questionId", questionId);
        resp.put("action", favorited ? "add" : "remove");
        resp.put("favorited", favorited);
        return ApiResponse.ok(resp).toEntity();
    }

    /**
     * 拉取收藏列表，按 ts 倒序。
     */
    @GetMapping("/favorite/list")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listFavorites(
            @RequestHeader(value = "X-Open-Id", required = false) String openId) {

        if (!hasText(openId)) {
            return failList(ResultCode.BAD_REQUEST, "缺少请求头 X-Open-Id");
        }

        WxUser user = userService.findByOpenId(openId);
        if (user == null) {
            return failList(ResultCode.NOT_FOUND, "用户不存在，请先调用 /user/report");
        }

        List<Map<String, Object>> list = userService.listFavorites(user.getId()).stream()
                .map(favorite -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("questionId", favorite.getQuestionId());
                    item.put("ts", favorite.getTs());
                    return item;
                })
                .toList();
        return ApiResponse.ok(list).toEntity();
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> fail(ResultCode code, String msg) {
        return ApiResponse.<Map<String, Object>>fail(code, msg).toEntity();
    }

    private static ResponseEntity<ApiResponse<List<Map<String, Object>>>> failList(
            ResultCode code, String msg) {
        return ApiResponse.<List<Map<String, Object>>>fail(code, msg).toEntity();
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private static Long parseTs(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
