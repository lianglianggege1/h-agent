---
name: optimize-image-to-video-prompt
description: Convert a user's request about an attached reference image into a concise MiniMax image-to-video prompt. Use when the user asks to animate, move, or create a video from the selected image.
---

# MiniMax Image-To-Video Prompting

1. Preserve the user's request as `originalPrompt`.
2. Treat the selected `referenceResourceId` as the required first frame.
3. Describe only the requested subject movement, environmental motion, and camera movement. Preserve the subject, composition, and visual identity unless the user explicitly asks to change them.
4. Call `image_to_video` with `referenceResourceId`, `originalPrompt`, and the optimized `prompt`.
5. Default to `MiniMax-Hailuo-2.3`, `6` seconds, `768P`, `promptOptimizer=false`, `fastPretreatment=false`, and `aigcWatermark=false`.
6. Do not wait for generation or query the task status. The completed video is added to the chat automatically.

Use MiniMax bracketed camera instructions only when they materially improve the requested motion. Ask one concise question only when the requested movement is missing or the user has not selected a reference image.
