package org.example.service;

import java.util.List;
import java.util.Objects;

import org.example.entity.UserFavorite;
import org.example.entity.WxUser;
import org.example.mapper.UserFavoriteMapper;
import org.example.mapper.WxUserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

@Service
public class UserService {

    private final WxUserMapper wxUserMapper;
    private final UserFavoriteMapper userFavoriteMapper;

    public UserService(WxUserMapper wxUserMapper, UserFavoriteMapper userFavoriteMapper) {
        this.wxUserMapper = wxUserMapper;
        this.userFavoriteMapper = userFavoriteMapper;
    }

    public WxUser findByOpenId(String openid) {
        if (!hasText(openid)) {
            return null;
        }
        return wxUserMapper.selectOne(
                new LambdaQueryWrapper<WxUser>().eq(WxUser::getOpenid, openid.trim()));
    }

    /**
     * 上报用户信息：首次插入，之后只更新非空的昵称/头像。
     */
    public ReportResult report(String openid, String nickname, String avatarUrl) {
        WxUser user = findByOpenId(openid);
        boolean created = false;
        if (user == null) {
            user = new WxUser();
            user.setOpenid(openid.trim());
            user.setNickname(trimToNull(nickname));
            user.setAvatarUrl(trimToNull(avatarUrl));
            wxUserMapper.insert(user);
            created = true;
        } else {
            boolean changed = false;
            if (hasText(nickname)) {
                user.setNickname(nickname.trim());
                changed = true;
            }
            if (hasText(avatarUrl)) {
                user.setAvatarUrl(avatarUrl.trim());
                changed = true;
            }
            if (changed) {
                wxUserMapper.updateById(user);
            }
        }
        return new ReportResult(user, created);
    }

    /**
     * 收藏最终状态：add 幂等写入（已存在则更新 ts），remove 幂等删除。
     *
     * @return true 表示当前为已收藏状态
     */
    public boolean setFavorite(Long userId, String questionId, String action, long ts) {
        boolean favorite = "add".equals(action);
        UserFavorite existing = userFavoriteMapper.selectOne(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getQuestionId, questionId));

        if (favorite) {
            if (existing == null) {
                UserFavorite record = new UserFavorite();
                record.setUserId(userId);
                record.setQuestionId(questionId);
                record.setTs(ts);
                try {
                    userFavoriteMapper.insert(record);
                } catch (DuplicateKeyException e) {
                    // 并发重复 add：唯一键兜底，改为更新 ts
                    updateTs(userId, questionId, ts);
                }
            } else if (!Objects.equals(existing.getTs(), ts)) {
                existing.setTs(ts);
                userFavoriteMapper.updateById(existing);
            }
        } else if (existing != null) {
            userFavoriteMapper.deleteById(existing.getId());
        }
        return favorite;
    }

    public List<UserFavorite> listFavorites(Long userId) {
        return userFavoriteMapper.selectList(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .orderByDesc(UserFavorite::getTs));
    }

    private void updateTs(Long userId, String questionId, long ts) {
        UserFavorite patch = new UserFavorite();
        patch.setTs(ts);
        userFavoriteMapper.update(patch, new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getQuestionId, questionId));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    public record ReportResult(WxUser user, boolean created) {
    }
}
