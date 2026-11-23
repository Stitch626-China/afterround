package com.jobpilot.modules.review.dto;

/** 转写片段（契约 ReviewTranscriptSegment）；时间相对音频起点，单位秒。 */
public record ReviewTranscriptSegmentResponse(
        Integer sequence,
        String speaker,
        String text,
        Double startTime,
        Double endTime) {
}
