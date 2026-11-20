package com.h.matrixteam;

/** 本轮执行的过程上报器；工具包装/模型监听据此把步骤与思考挂到当前 run。 */
public final class TeamProgressContext {

    private static final ThreadLocal<TeamProgressReporter> REPORTER = new ThreadLocal<>();

    private TeamProgressContext() {
    }

    public static void set(TeamProgressReporter reporter) {
        REPORTER.set(reporter);
    }

    public static TeamProgressReporter get() {
        return REPORTER.get();
    }

    public static void clear() {
        REPORTER.remove();
    }
}