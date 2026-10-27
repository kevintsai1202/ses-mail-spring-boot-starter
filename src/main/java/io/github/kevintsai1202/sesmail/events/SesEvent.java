package io.github.kevintsai1202.sesmail.events;

/** 從 SNS 通知解析出來、需要應用程式處理的 SES 事件（退信或檢舉） */
public sealed interface SesEvent permits SesBounce, SesComplaint {
}
