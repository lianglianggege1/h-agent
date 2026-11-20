package com.h.capabilities.seam.matrix;

import com.h.capabilities.seam.port.ResourceUrlPort;

/** team 场景 resourceId 即 mxc:// 地址，直接作为查看/下载引用返回。 */
public class TeamResourceUrls implements ResourceUrlPort {

    @Override
    public String view(String resourceId) {
        return resourceId;
    }

    @Override
    public String download(String resourceId) {
        return resourceId;
    }
}