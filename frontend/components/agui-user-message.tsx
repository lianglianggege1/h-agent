"use client";

import { CopilotChatUserMessage, type CopilotChatUserMessageProps } from "@copilotkit/react-core/v2";
import styles from "./agui-user-message.module.css";

function ReferenceUserMessageView(props: CopilotChatUserMessageProps) {
  const references = Array.isArray(props.message.content) ? props.message.content.flatMap((part, index) =>
    (part.type === "image" || part.type === "video" || part.type === "audio") && part.source.type === "url" && part.metadata?.hAgentReference === true
      ? [{ id: String(part.metadata.resourceId ?? index), url: part.source.value, kind: part.type }] : []) : [];
  if (!references.length) return <CopilotChatUserMessage {...props} />;
  return <CopilotChatUserMessage {...props}>
    {({ messageRenderer, toolbar }) => <div data-copilotkit data-testid="copilot-user-message"
      data-message-id={props.message.id}
      className={`copilotKitMessage copilotKitUserMessage cpk:flex cpk:flex-col cpk:items-end cpk:group cpk:pt-10 ${props.className ?? ""}`}>
      {messageRenderer}
      <div className={styles.references} aria-label="此消息引用的素材">
        {references.map((reference, index) => <a key={reference.id} href={reference.url} target="_blank" rel="noreferrer"
          className={styles.thumbnail} aria-label={`查看引用${reference.kind === "video" ? "视频" : reference.kind === "audio" ? "音频" : "图片"} ${index + 1}`} title="查看引用素材">
          {reference.kind === "image" ? <picture><img src={reference.url} alt={`引用图片 ${index + 1}`} /></picture>
            : reference.kind === "video" ? <><video src={reference.url} muted preload="metadata" /><span className={styles.play} aria-hidden="true">▶</span></>
            : <span className={styles.audio} aria-hidden="true">♫</span>}
        </a>)}
      </div>
      {toolbar}
    </div>}
  </CopilotChatUserMessage>;
}

export const ReferencedUserMessage = Object.assign(ReferenceUserMessageView, CopilotChatUserMessage);
