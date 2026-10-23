package org.example;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.common.ApiResponse;
import org.example.common.ResultCode;
import org.example.entity.WxUser;
import org.example.service.SessionService;
import org.example.service.UserService;
import org.example.service.WeChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信端用户接口。
 * 登录流程：/user/report 用 wx.login 的 code 换取 openid 并签发登录 token；
 * 后续接口只认 token，openid 由服务端从 token 解析，客户端不可直接传递，
 * 防止水平越权（伪造他人 openid 读写他人数据）。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;
    private final WeChatService weChatService;
    private final SessionService sessionService;

    public UserController(UserService userService, WeChatService weChatService,
                          SessionService sessionService) {
        this.userService = userService;
        this.weChatService = weChatService;
        this.sessionService = sessionService;
    }

    /**
     * 首次进入上报用户，用 wx.login 的 code 换取 openid，并签发登录 token。
     * body: { "appid": "wx...", "code": "...", "model": "iPhone 12/13 (Pro)" }
     * model 仅随请求传入，不落库。
     * 响应: { "token": "...", "userId": 1, "created": true }
     * openid 不返回给客户端，后续请求一律携带 token。
     */
    @PostMapping("/report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> report(
            @RequestBody(required = false) Map<String, Object> body) {

        Map<String, Object> data = body == null ? Map.of() : body;
        String appid = firstText(data.get("appid"), data.get("appId"));
        String code = firstText(data.get("code"));

        if (appid == null) {
            return fail(ResultCode.BAD_REQUEST, "appid 不能为空");
        }
        if (!weChatService.supportsAppId(appid)) {
            return fail(ResultCode.BAD_REQUEST, "appid 不匹配");
        }
        if (code == null) {
            return fail(ResultCode.BAD_REQUEST, "code 不能为空");
        }
        if (code.length() > 128) {
            return fail(ResultCode.BAD_REQUEST, "code 长度不能超过 128");
        }

        String openId;
        try {
            openId = weChatService.code2Session(code);
        } catch (WeChatService.WeChatException e) {
            return fail(ResultCode.UPSTREAM_ERROR, e.getMessage());
        }

        UserService.ReportResult result = userService.report(openId);
        WxUser user = result.user();
        String token = sessionService.createSession(user.getOpenid());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("userId", user.getId());
        resp.put("created", result.created());
        return ApiResponse.ok(resp).toEntity();
    }

    /**
     * 收藏增删（最终状态语义，非事件增量）。
     * body: { "token": "...", "questionId": "q1024", "action": "add", "ts": 1729580000000 }
     */
    @PostMapping("/favorite")
    public ResponseEntity<ApiResponse<Map<String, Object>>> favorite(
            @RequestBody(required = false) Map<String, Object> body) {

        Map<String, Object> data = body == null ? Map.of() : body;
        String token = firstText(data.get("token"));
        if (token == null) {
            return fail(ResultCode.BAD_REQUEST, "token 不能为空");
        }
        String openId = sessionService.resolveOpenid(token);
        if (openId == null) {
            return fail(ResultCode.UNAUTHORIZED, "未登录或登录已过期，请重新进入小程序");
        }

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
     * GET 没有请求体，token 通过查询参数传递：?token=xxx
     */
    @GetMapping("/favorite/list")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listFavorites(
            @RequestParam(value = "token", required = false) String token) {

        String openId = sessionService.resolveOpenid(token);
        if (openId == null) {
            return failList(ResultCode.UNAUTHORIZED, "未登录或登录已过期，请重新进入小程序");
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
}
