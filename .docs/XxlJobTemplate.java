package com.jidu.project.tool.ha.center.xxljob;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.CookieStore;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * @author hao.chen
 * @date 2022/8/3
 */
@Slf4j
@Component
public class XxlJobTemplate {

    @Value("${xxl.job.admin.addresses}")
    private String addresses;

    @Value("${xxl.job.admin.username}")
    private String username;

    @Value("${xxl.job.admin.password}")
    private String password;

    private String cookie;


    /**
     * 添加job
     *
     * @param addJob
     * @return
     */
    @SneakyThrows
    public Long addJob(AddXxlJobDto addJob) {
        Map<String, Object> paramMap = BeanMapUtil.beanToMap(addJob);
        log.info("开始创建任务 paramMap={}", addJob);
        JSONObject result = doRequest(XxlJobPathEnum.ADD, paramMap);
        log.info("创建任务成功 task_id={} paramMap={}", result.getLong("content"), paramMap);
        return result.getLong("content");
    }

    /**
     * 更新job
     *
     * @param updateXxlJob
     */
    public void updateJob(UpdateXxlJobDto updateXxlJob) {
        updateJob(JSONUtil.parseObj(updateXxlJob));
    }

    private void updateJob(Map<String, Object> paramMap) {
        JSONObject result = doRequest(XxlJobPathEnum.UPDATE, paramMap);
        if (HttpStatus.HTTP_OK != result.getInt("code")) {
            throw new RuntimeException(String.format("xxl-job-admin更新Job失败:%s", result.getStr("msg")));
        }
        log.info("更新任务成功 task_id={} paramMap={}", paramMap.get("id"), paramMap);
    }

    /**
     * 删除job
     *
     * @param jobId
     */
    public void removeJob(Long jobId) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("id", jobId);
        doRequest(XxlJobPathEnum.REMOVE, paramMap);
        log.info("删除任务成功 task_id={}", jobId);
    }

    /**
     * 执行一次
     *
     * @param jobId
     * @param param
     */
    public void triggerJob(Long jobId, String param) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("id", jobId);
        paramMap.put("executorParam", param);
        doRequest(XxlJobPathEnum.TRIGGER, paramMap);
        log.info("执行一次任务成功 task_id={} executorParam={}", jobId, param);
    }

    /**
     * 暂停任务
     *
     * @param jobId
     */
    public void stopJob(Long jobId) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("id", jobId);
        doRequest(XxlJobPathEnum.STOP, paramMap);
        log.info("暂停任务成功 task_id={}", jobId);
    }

    /**
     * 启动任务
     *
     * @param jobId
     */
    public void startJob(Long jobId) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("id", jobId);
        doRequest(XxlJobPathEnum.START, paramMap);
        log.info("启动任务成功 task_id={}", jobId);
    }


    /**
     * 获取登录cookie
     *
     * @return
     */
    private String getCookie() throws URISyntaxException, IOException {
        Map<String, Object> paramsMap = new HashMap();
        paramsMap.put("userName", username);
        paramsMap.put("password", password);

        CookieStore cookieStore = new BasicCookieStore();
        CloseableHttpClient client = HttpClients.custom().setDefaultCookieStore(cookieStore).build();
        HttpPost request = new HttpPost();
        request.setURI(new URI(String.format("%s%s", addresses, XxlJobPathEnum.LOGIN.getPath())));
        List nvps = new ArrayList();
        for (Iterator iter = paramsMap.keySet().iterator(); iter.hasNext(); ) {
            String name = (String) iter.next();
            String value = String.valueOf(paramsMap.get(name));
            nvps.add(new BasicNameValuePair(name, value));
        }
        request.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
        org.apache.http.HttpResponse response = client.execute(request);

        if (200 != response.getStatusLine().getStatusCode()) {
            throw new RuntimeException(String.format("xxl-job-admin登录失败:statusCode=%s", response.getStatusLine().getStatusCode()));
        }
        List<Cookie> cookies = cookieStore.getCookies();
         if (cookies.isEmpty()) {
            throw new RuntimeException(
                    String.format("xxl-job-admin登录失败:[userName=%s,password=%s]", username, password));
        }
        return cookies.get(0).getName()+"="+cookies.get(0).getValue();
    }

    /**
     * 远程调用xxl-job-admin
     *
     * @param xxlJobPathEnum
     * @param paramMap
     * @return
     */
    private JSONObject doRequest(XxlJobPathEnum xxlJobPathEnum, Map<String, Object> paramMap) {
        try {
            if (StrUtil.isBlank(cookie)) {
                cookie = getCookie();
            }
            /*HttpResponse response = HttpRequest.post(String.format("%s%s", addresses, xxlJobPathEnum.getPath()))
                    .cookie(cookie).form(paramMap).execute();*/

            HttpResponse response = HttpRequest.post(String.format("%s%s", addresses, xxlJobPathEnum.getPath()))
                    .cookie(cookie).form(paramMap).execute();
            if (HttpStatus.HTTP_OK != response.getStatus()) {
                log.error("xxl-job-admin{}失败! response={}", xxlJobPathEnum.getDesc(), response);
                throw new RuntimeException(String.format("xxl-job-admin%s请求失败:statusCode=%s",
                        xxlJobPathEnum.getDesc(), response.getStatus()));
            }
            JSONObject result = JSONUtil.parseObj(response.body());
            Integer code = result.getInt("code");
            if (code != null && HttpStatus.HTTP_OK != code) {
                log.error("xxl-job-admin{}失败! result={}", xxlJobPathEnum.getDesc(), result);
                throw new RuntimeException(
                        String.format("xxl-job-admin%s失败:msg=%s", xxlJobPathEnum.getDesc(), result.getStr("msg")));
            }
            return result;
        } catch (Exception e) {
            log.error("request xxljob error:", e);
        }
        return null;
    }
}
