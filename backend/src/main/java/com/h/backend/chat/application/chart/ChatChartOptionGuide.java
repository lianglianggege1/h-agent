package com.h.backend.chat.application.chart;

import java.util.List;

/** Version-specific examples available to the chat agent before it publishes an option. */
final class ChatChartOptionGuide {
    static final String TOOL_NAME = "get_chart_option_guide";
    static final List<String> TOPICS = List.of("reference", "cartesian", "connections", "bar-range", "gl", "map");

    private ChatChartOptionGuide() {}

    static String forTopic(String topic) {
        return switch (topic == null ? "reference" : topic) {
            case "cartesian" -> """
                    双坐标轴组合图。类目和数值列来自同一份 dataset。encode 指定字段，yAxisIndex 从 0 开始。
                    axisLabel.formatter 中的 percent 接受 0 到 1 的小数。
                    {"option":{
                      "tooltip":{"trigger":"axis"},"legend":{},
                      "dataset":{"source":[["日期","请求量","错误率"],["周一",100,0.02],["周二",180,0.03]]},
                      "xAxis":{"type":"category"},
                      "yAxis":[{"type":"value","name":"请求量"},{"type":"value","name":"错误率","axisLabel":{"formatter":{"$callback":"percent"}}}],
                      "series":[
                        {"name":"请求量","type":"bar","encode":{"x":"日期","y":"请求量"}},
                        {"name":"错误率","type":"line","yAxisIndex":1,"encode":{"x":"日期","y":"错误率"}}
                      ]
                    }}
                    需要长时间序列缩放时，可添加 dataZoom。显示堆叠时，将同一坐标轴上的系列设置为相同的 stack 名称。
                    """;
            case "connections" -> """
                    桑基图用 data 提供所有节点，links 提供流向。source 和 target 与节点 name 一致，value 是数值权重。
                    对存在循环依赖的网络使用 graph；桑基图要求无环。
                    {"option":{
                      "tooltip":{"trigger":"item"},
                      "series":[{"type":"sankey","orient":"horizontal",
                        "data":[{"name":"入口"},{"name":"搜索"},{"name":"数据库"}],
                        "links":[{"source":"入口","target":"搜索","value":40},{"source":"搜索","target":"数据库","value":24}]
                      }]
                    }}
                    """;
            case "bar-range" -> """
                    水平区间图用已注册的 barRange。每行数据依次为 [类目索引, 下界, 上界]。
                    把 [1,2] 映射到数值轴 x，类目索引 0 映射到 y。垂直图交换 xAxis/yAxis 与 encode.x/encode.y。
                    {"extensions":["bar-range"],"option":{
                      "tooltip":{"trigger":"item"},"xAxis":{"type":"value","name":"毫秒"},
                      "yAxis":{"type":"category","data":["API","数据库"]},
                      "series":[{"type":"custom","renderItem":"barRange","data":[[0,12,29],[1,5,36]],
                        "encode":{"x":[1,2],"y":0,"tooltip":[1,2]},
                        "itemPayload":{"barWidth":14,"borderRadius":3}
                      }]
                    }}
                    """;
            case "gl" -> """
                    ECharts GL 三维散点图。声明 gl 扩展，提供 grid3D 和三条坐标轴。
                    {"extensions":["gl"],"option":{
                      "grid3D":{},"xAxis3D":{"name":"X","type":"value"},
                      "yAxis3D":{"name":"Y","type":"value"},"zAxis3D":{"name":"Z","type":"value"},
                      "series":[{"type":"scatter3D","symbolSize":10,"data":[[1,2,3],[2,4,8]]}]
                    }}
                    曲面图 surface 使用规则网格，dataShape 为 [行数, 列数]。逐行排列点，同一行采用相同顺序的列坐标。
                    例如 data=[[0,0,12],[0,1,14],[0,2,16],[1,0,18],[1,1,20],[1,2,22]]，dataShape=[2,3]。
                    不规则样本可用 scatter3D 展示原始点。
                    """;
            case "map" -> """
                    show_chart 的 maps 参数将地图名称映射到 GeoJSON FeatureCollection，或当前会话中已存在的文件虚拟路径。
                    文件示例：maps={"china":"/china.geojson"}。文件须含完整的 JSON 对象和非空 features；地图名称与 option.geo.map 或 option.series[].map 一致。
                    使用已授权的边界数据。URL 应由可用的数据获取工具读取并保存到当前会话，然后提交已确认存在的虚拟文件路径。
                    填色地图的 data[].name 对应 feature 的 properties.name；visualMap 配置数值映射，地理坐标按经度、纬度排列。
                    边界数据无法取得时，说明缺少的覆盖范围；已有区域数值可按用户目标给出表格或地区对比图。
                    """;
            case "reference" -> """
                    当前聊天使用 Apache ECharts 6.1。
                    趋势、对比、数值关系和构成优先通过 show_chart 提交 intent、x、y、table 和可选 unit。
                    下面的配置示例用于高级图。将示例中的 option 对象序列化成 JSON 字符串，放入 show_chart 的 optionJson。extensions 单独提交。
                    官方配置手册：https://echarts.apache.org/zh/option.html
                    可按主题查询示例：cartesian（坐标和组合图）、connections（桑基与关系图）、
                    bar-range（注册区间图）、gl（三维）、map（GeoJSON）。
                    这里的示例覆盖常见配置，原生 option 也支持其余内置系列和组件。
                    高级图使用 dataset.source 或 series.data。source 说明数据来源、口径和单位，table 可随图提供核对数据。
                    option 中 formatter 可用 ECharts 字符串模板或已注册的 number / percent 回调，工具参数须为 JSON。
                    tooltip.formatter 支持字符串模板；tooltip.valueFormatter 使用注册回调，例如 {"$callback":"number","suffix":" 万元"}。
                    """;
            default -> throw new IllegalArgumentException("未知示例主题。可用主题：" + String.join("、", TOPICS));
        };
    }
}
