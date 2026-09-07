package com.jidu.project.tool.ha.center.xxljob;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 执行器：任务的绑定的执行器，任务触发调度时将会自动发现注册成功的执行器, 实现任务自动发现功能; 另一方面也可以方便的进行任务分组。每个任务必须绑定一个执行器, 可在 "执行器管理" 进行设置。
 * <p>
 * 　　任务描述：任务的描述信息，便于任务管理；
 * <p>
 * 　　路由策略：当执行器集群部署时，提供丰富的路由策略，包括； 　　　　　　FIRST（第一个）：固定选择第一个机器； 　　　　　　LAST（最后一个）：固定选择最后一个机器； 　　　　　　ROUND（轮询）：；
 * 　　　　　　RANDOM（随机）：随机选择在线的机器； 　　　　　　CONSISTENT_HASH（一致性HASH）：每个任务按照Hash算法固定选择某一台机器，且所有任务均匀散列在不同机器上。
 * 　　　　　　LEAST_FREQUENTLY_USED（最不经常使用）：使用频率最低的机器优先被选举； 　　　　　　LEAST_RECENTLY_USED（最近最久未使用）：最久为使用的机器优先被选举；
 * 　　　　　　FAILOVER（故障转移）：按照顺序依次进行心跳检测，第一个心跳检测成功的机器选定为目标执行器并发起调度；
 * 　　　　　　BUSYOVER（忙碌转移）：按照顺序依次进行空闲检测，第一个空闲检测成功的机器选定为目标执行器并发起调度；
 * 　　　　　　SHARDING_BROADCAST(分片广播)：广播触发对应集群中所有机器执行一次任务，同时系统自动传递分片参数；可根据分片参数开发分片任务； 　　Cron：触发任务执行的Cron表达式； 　　运行模式：
 * 　　　　　　BEAN模式：任务以JobHandler方式维护在执行器端；需要结合 "JobHandler" 属性匹配执行器中任务；
 * 　　　　　　GLUE模式(Java)：任务以源码方式维护在调度中心；该模式的任务实际上是一段继承自IJobHandler的Java类代码并 "groovy"
 * 源码方式维护，它在执行器项目中运行，可使用@Resource/@Autowire注入执行器里中的其他服务； 　　　　　　GLUE模式(Shell)：任务以源码方式维护在调度中心；该模式的任务实际上是一段 "shell" 脚本；
 * 　　　　　　GLUE模式(Python)：任务以源码方式维护在调度中心；该模式的任务实际上是一段 "python" 脚本； 　　　　　　GLUE模式(PHP)：任务以源码方式维护在调度中心；该模式的任务实际上是一段 "php" 脚本；
 * 　　　　　　GLUE模式(NodeJS)：任务以源码方式维护在调度中心；该模式的任务实际上是一段 "nodejs" 脚本； 　　　　　　GLUE模式(PowerShell)：任务以源码方式维护在调度中心；该模式的任务实际上是一段
 * "PowerShell" 脚本； 　　JobHandler：运行模式为 "BEAN模式" 时生效，对应执行器中新开发的JobHandler类“@JobHandler”注解自定义的value值；
 * 　　阻塞处理策略：调度过于密集执行器来不及处理时的处理策略； 　　　　　　单机串行（默认）：调度请求进入单机执行器后，调度请求进入FIFO队列并以串行方式运行；
 * 　　　　　　丢弃后续调度：调度请求进入单机执行器后，发现执行器存在运行的调度任务，本次请求将会被丢弃并标记为失败；
 * 　　　　　　覆盖之前调度：调度请求进入单机执行器后，发现执行器存在运行的调度任务，将会终止运行中的调度任务并清空队列，然后运行本地调度任务；
 * 　　子任务：每个任务都拥有一个唯一的任务ID(任务ID可以从任务列表获取)，当本任务执行结束并且执行成功时，将会触发子任务ID所对应的任务的一次主动调度。 　　任务超时时间：支持自定义任务超时时间，任务运行超时将会主动中断任务；
 * 　　失败重试次数；支持自定义任务失败重试次数，当任务失败时将会按照预设的失败重试次数主动进行重试； 　　报警邮件：任务调度失败时邮件通知的邮箱地址，支持配置多邮箱地址，配置多个邮箱地址时用逗号分隔； 　　负责人：任务的负责人；
 * 　　执行参数：任务执行所需的参数，多个参数时用逗号分隔，任务执行时将会把多个参数转换成数组传入；
 *
 * @author hao.chen
 * @date 2022/8/4
 */
@Data
@Accessors(chain = true)
public class AddXxlJobDto {

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
     * 调度类型 CRON 固定速度 NONE
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
     * 路由策略 默认随机: RANDOM; 一致性hash : CONSISTENT_HASH
     */
    private String executorRouteStrategy = "CONSISTENT_HASH";
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
