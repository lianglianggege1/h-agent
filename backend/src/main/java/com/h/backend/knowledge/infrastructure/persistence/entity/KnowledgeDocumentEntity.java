package com.h.backend.knowledge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("knowledge_document")
public class KnowledgeDocumentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("prompt_id")
    private Long promptId;

    @TableField("file_name")
    private String fileName;

    @TableField("source_type")
    private String sourceType;

    @TableField("file_type")
    private String fileType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("char_count")
    private Integer charCount;

    @TableField("segment_count")
    private Integer segmentCount;

    private String status;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("content_hash")
    private String contentHash;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
