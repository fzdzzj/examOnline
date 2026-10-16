package com.exam.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.entity.InviteCode;
import com.exam.auth.mapper.InviteCodeMapper;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;

/**
 * 教师邀请码服务：管理员生成/作废，教师在注册时凭有效码授予 TEACHER 角色。
 * 邀请码非单次使用（可重复用于多名教师），作废后立即失效。
 */
@Slf4j
@Service
public class InviteCodeService {

    /** 去除易混淆字符（0/O、1/I/L）后的邀请码字符集 */
    private static final String CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteCodeMapper inviteCodeMapper;

    public InviteCodeService(InviteCodeMapper inviteCodeMapper) {
        this.inviteCodeMapper = inviteCodeMapper;
    }

    /** 生成有效邀请码。 */
    public InviteCode create(Long adminId, String note) {
        InviteCode ic = new InviteCode();
        ic.setCode(generateCode());
        ic.setNote(note == null ? "" : note);
        ic.setStatus(0);
        ic.setUsedCount(0);
        ic.setCreatedBy(adminId);
        inviteCodeMapper.insert(ic);
        log.info("管理员 {} 生成教师邀请码 {}", adminId, ic.getCode());
        return ic;
    }

    /** 作废邀请码（status 0→1）。 */
    public void invalidate(Long id) {
        InviteCode ic = inviteCodeMapper.selectById(id);
        if (ic == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "邀请码不存在");
        }
        ic.setStatus(1);
        inviteCodeMapper.updateById(ic);
        log.info("教师邀请码已作废: id={}, code={}", id, ic.getCode());
    }

    /** 查询有效邀请码（未被作废）。 */
    public InviteCode getValidByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return inviteCodeMapper.selectOne(Wrappers.<InviteCode>lambdaQuery()
                .eq(InviteCode::getCode, code.trim())
                .eq(InviteCode::getStatus, 0));
    }

    /**
     * 标记一次使用（注册成功后调用）：定向原子 UPDATE（used_count = used_count + 1 且限定
     * status=0 有效状态），替代读-改-写整实体回写——
     * <ul>
     *   <li>并发注册同码时计数不丢失更新（SQL 侧自增）；</li>
     *   <li>并发「作废 + 使用」时 UPDATE 影响 0 行 → 抛 {@link ResponseCode#INVITE_CODE_INVALID}，
     *       不会把作废前读到的旧状态回写复活已作废邀请码。</li>
     * </ul>
     */
    public void markUsed(InviteCode ic) {
        int rows = inviteCodeMapper.update(null, Wrappers.<InviteCode>lambdaUpdate()
                .setSql("used_count = used_count + 1")
                .eq(InviteCode::getId, ic.getId())
                .eq(InviteCode::getStatus, 0));
        if (rows == 0) {
            throw new BusinessException(ResponseCode.INVITE_CODE_INVALID);
        }
    }

    /** 全部邀请码（管理员列表，按创建时间倒序）。 */
    public List<InviteCode> listAll() {
        return inviteCodeMapper.selectList(Wrappers.<InviteCode>lambdaQuery()
                .orderByDesc(InviteCode::getId));
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHARSET.charAt(RANDOM.nextInt(CHARSET.length())));
        }
        return sb.toString();
    }
}
