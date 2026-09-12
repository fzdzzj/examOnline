package com.exam.taking.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 自动保存响应：accepted=false 表示版本冲突被拒（多端并发时旧版本不覆盖新版本），
 * 客户端应以返回的 version 为准重新拉取/合并后再保存。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AutoSaveResponse {

    /** 是否受理本次保存；false=版本冲突（spec「多端冲突以最新为准」场景） */
    private boolean accepted;

    /** 服务端当前草稿版本（下次自动保存原样携带） */
    private Integer version;

    /** 服务端受理/持有的保存时间 */
    private LocalDateTime savedTime;
}
