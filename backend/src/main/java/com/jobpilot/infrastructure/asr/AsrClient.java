package com.jobpilot.infrastructure.asr;

/**
 * 语音转写抽象（FR4.1）：M3 用 OpenAI 兼容实现，后续可替换为厂商 SDK 或本地模型。
 */
public interface AsrClient {

    /** 是否已配置可用的 ASR 服务；未配置时音频入口应直接拒绝。 */
    boolean configured();

    /** 转写整段音频，返回纯文本；失败抛出运行时异常，由调用方记录重试。 */
    String transcribe(String fileName, String contentType, byte[] audio);
}
