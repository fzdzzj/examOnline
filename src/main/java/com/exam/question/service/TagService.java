package com.exam.question.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.question.dto.TagCreateRequest;
import com.exam.question.entity.QuestionTag;
import com.exam.question.entity.Tag;
import com.exam.question.entity.TagType;
import com.exam.question.mapper.QuestionTagMapper;
import com.exam.question.mapper.TagMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 扁平标签服务（spec「标签体系」需求）：学科/难度/题型/自定义四类。
 *
 * <p>标签定位为教师共享的全局资源：任何教师可创建/删除，删除题目关联随之清理。
 * 同名同类型查重在 Service 层完成（软删后允许重建同名标签，故不设数据库唯一键）。
 */
@Slf4j
@Service
public class TagService {

    private final TagMapper tagMapper;
    private final QuestionTagMapper questionTagMapper;

    public TagService(TagMapper tagMapper, QuestionTagMapper questionTagMapper) {
        this.tagMapper = tagMapper;
        this.questionTagMapper = questionTagMapper;
    }

    /** 创建标签：同类型下同名查重，重复创建报 1001。 */
    @Transactional
    public Tag create(TagCreateRequest request) {
        LoginUser operator = requireLogin();
        if (!TagType.isValid(request.getType())) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "非法标签类型");
        }
        String name = request.getName().trim();
        Long exists = tagMapper.selectCount(Wrappers.<Tag>lambdaQuery()
                .eq(Tag::getName, name)
                .eq(Tag::getType, request.getType()));
        if (exists > 0) {
            throw new BusinessException(ResponseCode.DATA_ALREADY_EXISTS, "同类型下同名标签已存在");
        }
        Tag tag = new Tag();
        tag.setName(name);
        tag.setType(request.getType());
        tag.setCreatedBy(operator.getId());
        tagMapper.insert(tag);
        log.info("教师 {} 创建标签 {}（{}）", operator.getId(), name, request.getType());
        return tag;
    }

    /** 标签列表：可按类型过滤（type 为空返回全部），按类型+ID 排序便于前端分组展示。 */
    public List<Tag> list(String type) {
        if (StringUtils.hasText(type)) {
            if (!TagType.isValid(type)) {
                throw new BusinessException(ResponseCode.BAD_REQUEST, "非法标签类型");
            }
            return tagMapper.selectList(Wrappers.<Tag>lambdaQuery()
                    .eq(Tag::getType, type)
                    .orderByAsc(Tag::getId));
        }
        return tagMapper.selectList(Wrappers.<Tag>lambdaQuery()
                .orderByAsc(Tag::getType)
                .orderByAsc(Tag::getId));
    }

    /**
     * 删除标签（软删）：关联行物理清理，避免残留脏关联；
     * 已打标的题目内容不受影响，只是不再带此标签。
     */
    @Transactional
    public void delete(Long id) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "标签不存在");
        }
        questionTagMapper.delete(Wrappers.<QuestionTag>lambdaQuery()
                .eq(QuestionTag::getTagId, id));
        tagMapper.deleteById(id);
        log.info("标签删除: id={} name={}", id, tag.getName());
    }

    private LoginUser requireLogin() {
        LoginUser operator = SecurityUtil.getCurrentUser();
        if (operator == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return operator;
    }
}
