---
name: optimize-video-prompt
description: Convert a user's natural-language video idea into a concise MiniMax text-to-video prompt before calling the text_to_video tool. Use whenever the user asks to create, generate, make, or improve a video from text, including requests that mention camera movement, cinematic shots, animation, or short video clips.
---

# MiniMax Text-To-Video Prompting

## Required Workflow

1. Preserve the user's original request as `originalPrompt`.
2. Extract the subject, core action, scene, visual style, lighting, composition, and camera movement.
3. Produce one coherent short-shot description as `prompt`. Use conservative details only when the user has not specified them.
4. Read `references/minimax-text-to-video.md` before selecting the model, duration, resolution, or a MiniMax camera instruction.
5. Call `text_to_video` with both values:
   - `originalPrompt`: the user's original request.
   - `prompt`: the optimized prompt to submit to MiniMax.
6. Do not wait for generation or call a status-query tool. The video task is asynchronous and will update in the chat automatically.

## Prompt Rules

- Keep a short video to one continuous scene or a simple ordered action.
- Preserve explicit subjects, actions, constraints, visual style, and camera directions.
- Do not invent subtitles, dialogue, logos, watermarks, brands, celebrity identities, or extra characters.
- Use a MiniMax bracketed camera instruction only when it materially improves the user's request.
- Put sequential camera instructions in the order they occur. Keep simultaneous instructions in one bracketed group and use no more than three.
- Keep the final prompt within 2000 characters.

## Tool Parameters

- Default to `MiniMax-Hailuo-2.3`, `6` seconds, and `768P` when the user gives no preference.
- Default to `promptOptimizer=false` after this Skill has optimized the prompt.
- Set `promptOptimizer=true` only when the user explicitly asks MiniMax to optimize the prompt.
- Set `fastPretreatment=true` only for a Hailuo model and only when the user prioritizes a faster prompt-optimization phase.
- Set `aigcWatermark=true` only when the user explicitly requests it.
- If the user explicitly asks for an unmodified prompt, set `prompt` equal to `originalPrompt` and set `promptOptimizer=false`.

## Clarification

Ask one concise question only when the missing subject or core action would materially change the resulting video. Do not block on optional details such as light, lens, or color; choose restrained defaults instead.
