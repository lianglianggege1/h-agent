package com.h.backend.auth.application;

/**
 * 工作台用户注册成功（注册事务内发布）。订阅方在事务提交后做开通类副作用，
 * 例如团队模块为新用户预开通专属 Matrix 身份与专属团队房间。
 */
public record UserRegisteredEvent(long userId) {
}