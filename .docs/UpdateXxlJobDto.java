package com.jidu.project.tool.ha.center.xxljob;

import lombok.Data;

/**
 * @author hao.chen
 * @date 2022/8/3
 */
@Data
public class UpdateXxlJobDto {

    /**
     * 任务id
     */
    private Long id;

    /**
     * 执行器主键ID
     */
    private int jobGroup;
    /**
     * job描述
     */
    private String jobDesc;
    /**
     * 负责人
     */
    private String author;
    /**
     * 报警邮件
     */
    private String alarmEmail;
    /**
     * 调度类型 CRON 固定速度
     */
    private String scheduleType = "CRON";
    /**
     * 调度配置，值含义取决于调度类型
     */
    private String jobCron;
    /**
     * 运行模式 BEAN GLUE(java)
     */
    private String glueType = "BEAN";
    /**
     * 运行来源
     */
    private String glueSource;
    /**
     * 执行器，任务Handler名称
     */
    private String executorHandler;
    /**
     * 任务参数
     */
    private String executorParam = "";
    /**
     * 路由策略 默认随机
     */
    private String executorRouteStrategy = "RANDOM";
    /**
     * 阻塞处理策略 默认单机串行
     */
    private String executorBlockStrategy = "SERIAL_EXECUTION";
    /**
     * 调度过期策略
     */
    private String misfireStrategy = "FIRE_ONCE_NOW";
    /**
     * 任务超时时间
     */
    private Integer executorTimeout = 0;
    /**
     * 失败重试次数
     */
    private Integer executorFailRetryCount = 0;
    /**
     * 调度状态：0-停止，1-运行
     */
    private Integer triggerStatus = 0;
    /**
     * 上次调度的时间
     */
    private Integer triggerLastTime = 0;
    /**
     * 下次调度的时间
     */
    private Integer triggerNextTime = 0;
    /**
     * 子任务id
     */
    private String childJobId;
}
