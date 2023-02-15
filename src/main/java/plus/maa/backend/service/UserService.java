package plus.maa.backend.service;

import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.lang.Assert;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTPayload;
import cn.hutool.jwt.JWTUtil;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import plus.maa.backend.common.MaaStatusCode;
import plus.maa.backend.common.utils.converter.MaaUserConverter;
import plus.maa.backend.controller.request.*;
import plus.maa.backend.controller.response.MaaLoginRsp;
import plus.maa.backend.controller.response.MaaResult;
import plus.maa.backend.controller.response.MaaResultException;
import plus.maa.backend.controller.response.MaaUserInfo;
import plus.maa.backend.service.model.LoginUser;
import plus.maa.backend.repository.RedisCache;
import plus.maa.backend.repository.UserRepository;
import plus.maa.backend.repository.entity.MaaUser;

import java.time.LocalDateTime;
import java.util.*;

/**
 * @author AnselYuki
 */
@Setter
@Service
@RequiredArgsConstructor
public class UserService {
    private static final String REDIS_KEY_PREFIX_LOGIN = "LOGIN:";
    private final AuthenticationManager authenticationManager;
    private final RedisCache redisCache;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${maa-copilot.jwt.secret}")
    private String secret;
    @Value("${maa-copilot.jwt.expire}")
    private int expire;

    private LoginUser getLoginUserByToken(String token) {
        JWT jwt = JWTUtil.parseToken(token);
        String redisKey = buildUserCacheKey(jwt.getPayload("userId").toString());
        return redisCache.getCache(redisKey, LoginUser.class);
    }

    /**
     * 登录方法
     *
     * @param loginDTO 登录参数
     * @return 携带了token的封装类
     */
    public MaaResult<MaaLoginRsp> login(LoginDTO loginDTO) {
        //使用 AuthenticationManager 中的 authenticate 进行用户认证
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(loginDTO.getEmail(), loginDTO.getPassword());
        Authentication authenticate;
        authenticate = authenticationManager.authenticate(authenticationToken);
        //若认证失败，给出相应提示
        if (Objects.isNull(authenticate)) {
            throw new MaaResultException("登陆失败");
        }
        //若认证成功，使用UserID生成一个JwtToken,Token存入ResponseResult返回
        LoginUser principal = (LoginUser) authenticate.getPrincipal();
        String userId = String.valueOf(principal.getMaaUser().getUserId());
        String token = RandomStringUtils.random(16, true, true);
        DateTime now = DateTime.now();
        DateTime newTime = now.offsetNew(DateField.SECOND, expire);
        //签发JwtToken，从上到下为设置签发时间，过期时间与生效时间
        Map<String, Object> payload = new HashMap<>(4) {
            {
                put(JWTPayload.ISSUED_AT, now.getTime());
                put(JWTPayload.EXPIRES_AT, newTime.getTime());
                put(JWTPayload.NOT_BEFORE, now.getTime());
                put("userId", userId);
                put("token", token);
            }
        };

        //把完整的用户信息存入Redis，UserID作为Key
        String cacheKey = buildUserCacheKey(userId);
        redisCache.updateCache(cacheKey, LoginUser.class, principal, cacheUser -> {
            String cacheToken = cacheUser.getToken();
            if (cacheToken != null && !"".equals(cacheToken)) {
                payload.put("token", cacheToken);
            } else {
                cacheUser.setToken(token);
            }
            return cacheUser;
        }, expire);

        String jwt = JWTUtil.createToken(payload, secret.getBytes());

        MaaLoginRsp rsp = new MaaLoginRsp();
        rsp.setToken(jwt);
        rsp.setValidAfter(LocalDateTime.now().toString());
        rsp.setValidBefore(newTime.toLocalDateTime().toString());
        rsp.setRefreshToken("");
        rsp.setRefreshTokenValidBefore("");
        rsp.setUserInfo(MaaUserConverter.INSTANCE.convert(principal.getMaaUser()));

        return MaaResult.success("登录成功", rsp);
    }

    /**
     * 修改密码
     *
     * @param loginUser 当前用户
     * @param password  新密码
     * @return 修改成功响应
     */
    public MaaResult<Void> modifyPassword(LoginUser loginUser, String password) {
        MaaUser user = loginUser.getMaaUser();
        //修改密码的逻辑
        String newPassword = new BCryptPasswordEncoder().encode(password);
        user.setPassword(newPassword);
        userRepository.save(user);

        //以下更新jwt-token并重新签发jwt
        String newJwtToken = RandomStringUtils.random(16, true, true);
        DateTime now = DateTime.now();
        DateTime newTime = now.offsetNew(DateField.SECOND, expire);
        Map<String, Object> payload = new HashMap<>(4) {
            {
                put(JWTPayload.ISSUED_AT, now.getTime());
                put(JWTPayload.EXPIRES_AT, newTime.getTime());
                put(JWTPayload.NOT_BEFORE, now.getTime());
                put("userId", user.getUserId());
                put("token", newJwtToken);
            }
        };
        String redisKey = buildUserCacheKey(user.getUserId());
        //把更新后的MaaUser对象重新塞回去..
        loginUser.setMaaUser(user);
        redisCache.updateCache(redisKey, LoginUser.class, loginUser, cacheUser -> {
            cacheUser.setToken(newJwtToken);
            return cacheUser;
        }, expire);

        String newJwt = JWTUtil.createToken(payload, secret.getBytes());
        //TODO 通知客户端更新jwt

        return MaaResult.success(null);
    }

    /**
     * 用户注册
     *
     * @param registerDTO 传入用户参数
     * @return 返回注册成功的用户摘要（脱敏）
     */
    public MaaResult<MaaUserInfo> register(RegisterDTO registerDTO) {
        String encode = new BCryptPasswordEncoder().encode(registerDTO.getPassword());
        MaaUser user = new MaaUser();
        BeanUtils.copyProperties(registerDTO, user);
        user.setPassword(encode);
        MaaUserInfo userInfo;
        try {
            MaaUser save = userRepository.save(user);
            userInfo = new MaaUserInfo(save);
        } catch (DuplicateKeyException e) {
            return MaaResult.fail(10001, "用户已存在");
        }
        emailService.sendActivateUrl(user.getEmail());
        return MaaResult.success(userInfo);
    }

    /**
     * 通过传入的JwtToken来获取当前用户的信息
     *
     * @param loginUser   当前用户
     * @param activateDTO 邮箱激活码
     * @return 用户信息封装
     */
    public MaaResult<Void> activateUser(LoginUser loginUser, ActivateDTO activateDTO) {
        if (Objects.equals(loginUser.getMaaUser().getStatus(), 1)) {
            return MaaResult.success();
        }
        String email = loginUser.getMaaUser().getEmail();
        emailService.verifyVCode(email, activateDTO.getToken());
        MaaUser user = loginUser.getMaaUser();
        user.setStatus(1);
        userRepository.save(user);
        updateLoginUserPermissions(1, user.getUserId());
        return MaaResult.success();
    }

    /**
     * 更新用户密码
     *
     * @param loginUser 当前用户
     * @param updateDTO 更新参数
     * @return 成功响应
     */
    public MaaResult<Void> updateUserInfo(LoginUser loginUser, UserInfoUpdateDTO updateDTO) {
        MaaUser user = loginUser.getMaaUser();
        user.updateAttribute(updateDTO);
        userRepository.save(user);
        redisCache.setCache(buildUserCacheKey(user.getUserId()), loginUser);
        return MaaResult.success(null);
    }

    /**
     * 发送验证码，用户信息从token中获取
     *
     * @param loginUser 当前用户
     * @return 成功响应
     */
    public MaaResult<Void> sendEmailCode(LoginUser loginUser) {
        Assert.state(Objects.equals(loginUser.getMaaUser().getStatus(), 0),
                "用户已经激活，无法再次发送验证码");
        String email = loginUser.getEmail();
        emailService.sendVCode(email);
        return MaaResult.success(null);
    }

    /**
     * 刷新token
     *
     * @param token token
     * @return 成功响应
     */
    public MaaResult<Void> refreshToken(String token) {
        //TODO 刷新JwtToken
        return null;
    }

    /**
     * 通过邮箱激活码更新密码
     *
     * @param passwordResetDTO 通过邮箱修改密码请求
     * @return 成功响应
     */
    public MaaResult<Void> modifyPasswordByActiveCode(PasswordResetDTO passwordResetDTO) {
        emailService.verifyVCode(passwordResetDTO.getEmail(), passwordResetDTO.getActiveCode());
        LoginUser loginUser = new LoginUser();
        MaaUser maaUser = userRepository.findByEmail(passwordResetDTO.getEmail());
        loginUser.setMaaUser(maaUser);
        return modifyPassword(loginUser, passwordResetDTO.getPassword());
    }

    /**
     * 根据邮箱校验用户是否存在
     *
     * @param email 用户邮箱
     */
    public void checkUserExistByEmail(String email) {
        if (null == userRepository.findByEmail(email)) {
            throw new MaaResultException(MaaStatusCode.MAA_USER_NOT_FOUND);
        }
    }

    /**
     * 激活账户
     *
     * @param activateDTO uuid
     */
    public void activateAccount(EmailActivateReq activateDTO) {
        String uuid = activateDTO.getNonce();
        String email = redisCache.getCache("UUID:" + uuid, String.class);
        Assert.notNull(email, "链接已过期");
        MaaUser user = userRepository.findByEmail(email);

        if (Objects.equals(user.getStatus(), 1)) {
            redisCache.removeCache("UUID:" + uuid);
            return;
        }
        //激活账户
        user.setStatus(1);
        userRepository.save(user);

        updateLoginUserPermissions(1, user.getUserId());
        //清除缓存
        redisCache.removeCache("UUID:" + uuid);
    }

    /**
     * 实时更新用户权限(更新redis缓存中的用户权限)
     *
     * @param permissions 权限值
     * @param userId      userId
     */
    private void updateLoginUserPermissions(int permissions, String userId) {
        LoginUser loginUser;
        //用户为登录 直接返回
        try {
            loginUser = (LoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        } catch (ClassCastException e) {
            return;
        }
        String cacheId = buildUserCacheKey(userId);

        redisCache.updateCache(cacheId, LoginUser.class, loginUser, cacheUser -> {
            Set<String> p = cacheUser.getPermissions();

            //更新权限数据
            cacheUser.getMaaUser().setStatus(permissions);
            for (int i = 0; i <= permissions; i++) {
                p.add(Integer.toString(i));
            }
            cacheUser.setPermissions(p);

            return cacheUser;
        }, expire);
    }

    private static String buildUserCacheKey(String userId) {
        return REDIS_KEY_PREFIX_LOGIN + userId;
    }
}
