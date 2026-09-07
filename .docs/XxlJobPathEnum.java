package com.jidu.project.tool.ha.center.xxljob;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author hao.chen
 * @date 2022/8/3
 */
@AllArgsConstructor
@Getter
public enum XxlJobPathEnum {
    LOGIN("/login", "登录"),
    ADD("/jobinfo/add", "添加Job"),
    UPDATE("/jobinfo/update", "更新Job"),
    TRIGGER("/jobinfo/trigger", "立即执行"),
    START("/jobinfo/start", "启动任务"),
    STOP("/jobinfo/stop", "暂停任务"),
    REMOVE("/jobinfo/remove", "删除Job"),
    PAGE_JOB("/jobinfo/pageList", "查询Job"),
    PAGE_GROUP("/jobgroup/pageList", "查询Job组");

    private String path;
    private String desc;
}
