package com.biligrab.downloader;

import java.util.ArrayList;
import java.util.List;

/** 视频信息与数据模型。 */
public final class Model {

    private Model() {
    }

    /** 稿件信息。 */
    public static final class Video {
        public String bvid = "";
        public long aid;
        public String title = "";
        public String owner = "";
        public String cover = "";
        public String desc = "";
        public int duration;
        public final List<Part> pages = new ArrayList<>();

        public boolean isMultiPart() {
            return pages.size() > 1;
        }
    }

    /** 分 P 信息。 */
    public static final class Part {
        public int index = 1;
        public long cid;
        public String title = "";
        public int duration;

        @Override
        public String toString() {
            return title;
        }
    }

    /** 一条可下载的媒体流。 */
    public static final class Stream {
        public String url = "";
        public final List<String> backups = new ArrayList<>();
        public int quality;
        public int codecId;
        public long bandwidth;
        public int width;
        public int height;
        public String mimeType = "";

        /** 带兜底地址的候选列表，主地址失败时按序重试。 */
        public List<String> candidates() {
            List<String> all = new ArrayList<>();
            if (!url.isEmpty()) {
                all.add(url);
            }
            all.addAll(backups);
            return all;
        }
    }

    /** 一次 playurl 解析的完整结果。 */
    public static final class PlayInfo {
        public final List<Stream> videos = new ArrayList<>();
        public final List<Stream> audios = new ArrayList<>();
        /** 展示用画质描述，如 “1080P 高清”。 */
        public String qualityDesc = "";
        public boolean audioOnlySupported;
        /** 画质档位 qn → 中文描述，来源为 support_formats。 */
        public final java.util.LinkedHashMap<Integer, String> qualities = new java.util.LinkedHashMap<>();

        public Stream videoByQuality(int qn, boolean preferAvc) {
            Stream best = null;
            for (Stream s : videos) {
                if (s.quality != qn) {
                    continue;
                }
                if (best == null) {
                    best = s;
                }
                // codecid: 7=AVC(H.264) 12=HEVC 13=AV1。AVC 兼容性最好，优先。
                if (preferAvc && s.codecId == 7) {
                    return s;
                }
            }
            if (best != null) {
                return best;
            }
            // 目标画质不存在时退化为码率最高的可用流
            for (Stream s : videos) {
                if (best == null || s.bandwidth > best.bandwidth) {
                    best = s;
                }
            }
            return best;
        }

        public Stream bestAudio() {
            Stream best = null;
            for (Stream s : audios) {
                if (best == null || s.bandwidth > best.bandwidth) {
                    best = s;
                }
            }
            return best;
        }
    }

    /** 一个下载任务。 */
    public static final class Task {
        public String bvid = "";
        public long cid;
        public String title = "";
        public String partTitle = "";
        public int qn = 80;
        public boolean audioOnly;

        public String displayName() {
            String base = sanitize(title);
            if (partTitle != null && !partTitle.isEmpty() && !partTitle.equals(title)) {
                base = base + " - " + sanitize(partTitle);
            }
            return base;
        }

        private static String sanitize(String s) {
            if (s == null) {
                return "video";
            }
            StringBuilder sb = new StringBuilder(s.length());
            for (char c : s.toCharArray()) {
                if ("\\/:*?\"<>|\r\n\t".indexOf(c) >= 0) {
                    sb.append('_');
                } else {
                    sb.append(c);
                }
            }
            String out = sb.toString().trim();
            if (out.length() > 80) {
                out = out.substring(0, 80);
            }
            return out.isEmpty() ? "video" : out;
        }
    }

    /** codecid → 可读名称。 */
    public static String codecName(int codecId) {
        switch (codecId) {
            case 7:
                return "AVC/H.264";
            case 12:
                return "HEVC/H.265";
            case 13:
                return "AV1";
            default:
                return "codec#" + codecId;
        }
    }

    /** DASH 音频档位 id → 可读名称。 */
    public static String audioName(int id) {
        switch (id) {
            case 30216:
                return "64K";
            case 30232:
                return "132K";
            case 30280:
                return "192K";
            case 30250:
                return "杜比全景声";
            case 30251:
                return "Hi-Res 无损";
            default:
                return "音频#" + id;
        }
    }
}
