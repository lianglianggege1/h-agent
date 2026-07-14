# MiniMax Text-To-Video Reference

## Valid Model and Format Combinations

| Model | Duration | Resolution |
|---|---:|---|
| `MiniMax-Hailuo-2.3` | 6 seconds | `768P`, `1080P` |
| `MiniMax-Hailuo-2.3` | 10 seconds | `768P` |
| `MiniMax-Hailuo-02` | 6 seconds | `768P`, `1080P` |
| `MiniMax-Hailuo-02` | 10 seconds | `768P` |
| `T2V-01-Director` | 6 seconds | `720P` |
| `T2V-01` | 6 seconds | `720P` |

`fastPretreatment` is valid only for Hailuo models.

## Camera Instructions

Use Chinese bracketed instructions exactly as shown when precise movement is needed:

| Motion | Instruction |
|---|---|
| Move left/right | `[左移]`, `[右移]` |
| Pan left/right | `[左摇]`, `[右摇]` |
| Dolly in/out | `[推进]`, `[拉远]` |
| Rise/fall | `[上升]`, `[下降]` |
| Tilt up/down | `[上摇]`, `[下摇]` |
| Zoom in/out | `[变焦推近]`, `[变焦拉远]` |
| Other | `[晃动]`, `[跟随]`, `[固定]` |

Examples:

```text
雨后的城市街道，一辆黄色出租车缓慢驶过反光的路面，霓虹灯映在水洼中，[低角度，推进]，电影感夜景。
```

```text
一只橘猫跳上窗台，晨光穿过薄纱窗帘，[跟随]，柔和写实风格。
```
