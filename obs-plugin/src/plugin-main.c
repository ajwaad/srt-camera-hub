/*
SRT Source Plugin for OBS Studio
Copyright (C) 2025 SRT Camera Hub

This plugin adds a native "SRT Source" to OBS Studio's Add Source menu.
It uses FFmpeg (bundled with OBS) to pull SRT video streams via the
srt:// protocol and renders them as OBS video sources.

Architecture:
  SRT Stream → avformat (FFmpeg) → avcodec (decode) → swscale (convert) → OBS output

Fix notes (v1.1):
  - Stream now starts on create/update, not just activate.
    OBS only calls activate() when the source is visible on program output,
    so sources added to a scene but not yet "live" would never start.
  - Fixed sws_scale height parameter (use frame height, not codec height).
  - Added resolution-change detection to recreate swscale context.
  - Proper thread lifecycle management.
*/

#include <obs-module.h>
#include <plugin-support.h>
#include <util/threading.h>
#include <util/platform.h>

#ifdef _WIN32
#include <windows.h>
#define sleep_ms(ms) Sleep(ms)
#else
#include <unistd.h>
#define sleep_ms(ms) usleep((ms) * 1000)
#endif

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/error.h>
#include <libswscale/swscale.h>

/* ── Constants ──────────────────────────────────────────────────────────────── */

#define SETTING_SRT_URL   "srt_url"
#define SETTING_LATENCY   "latency"
#define SETTING_RECONNECT "reconnect"

#define DEFAULT_LATENCY   120000   /* microseconds = 120ms */
#define RECONNECT_DELAY_MS 2000
#define MAX_RECONNECT_RETRIES 10

#define blog(level, fmt, ...) \
	blog(level, "[srt-source] " fmt, ##__VA_ARGS__)

/* ── Source context structure ───────────────────────────────────────────────── */

struct srt_source_context {
	/* OBS state */
	obs_source_t *source;

	/* FFmpeg state */
	AVFormatContext *fmt_ctx;
	AVCodecContext  *codec_ctx;
	AVFrame         *decoded_frame;
	AVPacket        *packet;
	struct SwsContext *sws_ctx;
	int              video_stream_idx;

	/* Resolution tracking for swscale invalidation */
	int              last_sws_src_w;
	int              last_sws_src_h;
	int              last_sws_dst_w;
	int              last_sws_dst_h;
	enum AVPixelFormat last_sws_pix_fmt;

	/* Threading */
	pthread_t        decode_thread;
	volatile bool    thread_running;
	volatile bool    thread_stop;

	/* Settings */
	char            *srt_url;
	int              latency_us;
	bool             reconnect_enabled;

	/* Stats */
	uint64_t         frames_decoded;
	uint64_t         frames_dropped;
	int              reconnect_attempts;

	/* Timestamp Sync */
	uint64_t         first_pts_ns;
	uint64_t         first_sys_ns;
};

/* ── Forward declarations ───────────────────────────────────────────────────── */

static const char *srt_source_get_name(void *type_data);
static void *srt_source_create(obs_data_t *settings, obs_source_t *source);
static void  srt_source_destroy(void *data);
static void  srt_source_get_defaults(obs_data_t *settings);
static obs_properties_t *srt_source_get_properties(void *data);
static void  srt_source_update(void *data, obs_data_t *settings);
static void  srt_source_activate(void *data);
static void  srt_source_deactivate(void *data);
static void  srt_source_video_tick(void *data, float seconds);
static uint32_t srt_source_get_width(void *data);
static uint32_t srt_source_get_height(void *data);

/* ── FFmpeg init and cleanup ────────────────────────────────────────────────── */

static bool ffmpeg_initialized = false;

static void init_ffmpeg(void)
{
	if (!ffmpeg_initialized) {
		av_log_set_level(AV_LOG_WARNING);
		ffmpeg_initialized = true;
	}
}

/* ── FFmpeg stream opening ──────────────────────────────────────────────────── */

static int interrupt_cb(void *ctx)
{
	struct srt_source_context *s = (struct srt_source_context *)ctx;
	return s->thread_stop ? 1 : 0;
}

static int open_srt_stream(struct srt_source_context *ctx)
{
	int ret;
	const AVCodec *codec = NULL;
	AVDictionary *opts = NULL;

	blog(LOG_INFO, "Opening SRT stream: %s", ctx->srt_url ? ctx->srt_url : "(null)");

	/* Allocate format context */
	ctx->fmt_ctx = avformat_alloc_context();
	if (!ctx->fmt_ctx) {
		blog(LOG_ERROR, "Failed to allocate AVFormatContext");
		return -1;
	}

	/* Set interrupt callback so we can abort blocking operations */
	ctx->fmt_ctx->interrupt_callback.callback = interrupt_cb;
	ctx->fmt_ctx->interrupt_callback.opaque = ctx;

	/* Set SRT-specific options: longer timeout for listener mode */
	av_dict_set(&opts, "timeout", "5000000", 0);  /* 5 seconds timeout */
	av_dict_set(&opts, "rw_timeout", "5000000", 0);

	/* Ultra-low latency format options */
	av_dict_set(&opts, "fflags", "nobuffer", 0);
	av_dict_set(&opts, "flags", "low_delay", 0);

	/* Open the SRT URL */
	ret = avformat_open_input(&ctx->fmt_ctx, ctx->srt_url, NULL, &opts);
	av_dict_free(&opts);

	if (ret < 0) {
		char errbuf[256];
		av_strerror(ret, errbuf, sizeof(errbuf));
		blog(LOG_ERROR, "Cannot open SRT stream: %s (error: %s)",
		     ctx->srt_url, errbuf);
		return ret;
	}

	/* Restrict probing to minimize startup delay */
	ctx->fmt_ctx->probesize = 32000;
	ctx->fmt_ctx->max_analyze_duration = 0;

	/* Find stream info */
	ret = avformat_find_stream_info(ctx->fmt_ctx, NULL);
	if (ret < 0) {
		blog(LOG_ERROR, "Cannot find stream information");
		return ret;
	}

	/* Find the first video stream */
	ctx->video_stream_idx = -1;
	for (unsigned int i = 0; i < ctx->fmt_ctx->nb_streams; i++) {
		if (ctx->fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
			ctx->video_stream_idx = i;
			break;
		}
	}

	if (ctx->video_stream_idx < 0) {
		blog(LOG_ERROR, "No video stream found in SRT input");
		return -1;
	}

	AVCodecParameters *codecpar = ctx->fmt_ctx->streams[ctx->video_stream_idx]->codecpar;

	/* Find decoder */
	codec = avcodec_find_decoder(codecpar->codec_id);
	if (!codec) {
		blog(LOG_ERROR, "Unsupported codec: %d", codecpar->codec_id);
		return -1;
	}

	/* Allocate codec context */
	ctx->codec_ctx = avcodec_alloc_context3(codec);
	if (!ctx->codec_ctx) {
		blog(LOG_ERROR, "Failed to allocate codec context");
		return -1;
	}

	/* Copy codec parameters */
	ret = avcodec_parameters_to_context(ctx->codec_ctx, codecpar);
	if (ret < 0) {
		blog(LOG_ERROR, "Failed to copy codec params");
		return ret;
	}

	/* Enable low-delay decoding */
	ctx->codec_ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
	ctx->codec_ctx->flags2 |= AV_CODEC_FLAG2_FAST;

	/* Enable multi-threaded decoding (slice only to avoid frame delay) */
	ctx->codec_ctx->thread_count = 0; /* auto-detect */
	ctx->codec_ctx->thread_type = FF_THREAD_SLICE;

	/* Open codec */
	ret = avcodec_open2(ctx->codec_ctx, codec, NULL);
	if (ret < 0) {
		blog(LOG_ERROR, "Cannot open codec");
		return ret;
	}

	blog(LOG_INFO, "SRT stream opened: %dx%d, codec: %s, pix_fmt: %d",
	     ctx->codec_ctx->width, ctx->codec_ctx->height,
	     codec->name, ctx->codec_ctx->pix_fmt);

	return 0;
}

/* ── Cleanup FFmpeg resources ───────────────────────────────────────────────── */

static void close_srt_stream(struct srt_source_context *ctx)
{
	/* Free swscale context */
	if (ctx->sws_ctx) {
		sws_freeContext(ctx->sws_ctx);
		ctx->sws_ctx = NULL;
	}

	/* Reset resolution tracking */
	ctx->last_sws_src_w = 0;
	ctx->last_sws_src_h = 0;
	ctx->last_sws_dst_w = 0;
	ctx->last_sws_dst_h = 0;
	ctx->last_sws_pix_fmt = AV_PIX_FMT_NONE;

	/* Free decoded frame */
	if (ctx->decoded_frame) {
		av_frame_free(&ctx->decoded_frame);
	}

	/* Free packet */
	if (ctx->packet) {
		av_packet_free(&ctx->packet);
	}

	/* Close codec */
	if (ctx->codec_ctx) {
		avcodec_free_context(&ctx->codec_ctx);
	}

	/* Close format context */
	if (ctx->fmt_ctx) {
		avformat_close_input(&ctx->fmt_ctx);
	}

	blog(LOG_INFO, "SRT stream closed. Frames decoded: %llu, dropped: %llu",
	     (unsigned long long)ctx->frames_decoded,
	     (unsigned long long)ctx->frames_dropped);
}

/* ── Frame conversion and output (FFmpeg → OBS) ─────────────────────────────── */

static void output_frame_to_obs(struct srt_source_context *ctx, AVFrame *src)
{
	int src_w = src->width;
	int src_h = src->height;
	enum AVPixelFormat src_fmt = (enum AVPixelFormat)src->format;

	if (src_w <= 0 || src_h <= 0)
		return;

	/* 
	 * Optimization: If the decoded frame is already in a format OBS natively 
	 * supports (like YUV420P or NV12), we can completely bypass the CPU-heavy 
	 * sws_scale conversion and let OBS handle the color space conversion 
	 * on the GPU shader! This saves massive CPU usage.
	 */
	struct obs_source_frame frame = {0};
	frame.width       = src_w;
	frame.height      = src_h;

	/* Calculate smooth monotonic timestamp based on FFmpeg PTS */
	uint64_t frame_ts = 0;
	if (ctx->fmt_ctx && ctx->video_stream_idx >= 0 && src->best_effort_timestamp != AV_NOPTS_VALUE) {
		AVStream *st = ctx->fmt_ctx->streams[ctx->video_stream_idx];
		uint64_t pts_ns = src->best_effort_timestamp * 1000000000ULL * st->time_base.num / st->time_base.den;
		
		if (ctx->first_pts_ns == 0) {
			ctx->first_pts_ns = pts_ns;
			ctx->first_sys_ns = os_gettime_ns();
		}
		
		frame_ts = ctx->first_sys_ns + (pts_ns - ctx->first_pts_ns);
		
		/* Resync if drift exceeds 200ms */
		uint64_t current_time = os_gettime_ns();
		if (current_time > frame_ts + 200000000ULL || frame_ts > current_time + 200000000ULL) {
			ctx->first_pts_ns = pts_ns;
			ctx->first_sys_ns = current_time;
			frame_ts = current_time;
		}
	} else {
		frame_ts = os_gettime_ns();
	}
	frame.timestamp = frame_ts;

	/* Map FFmpeg color range to OBS */
	if (src->color_range == AVCOL_RANGE_JPEG || src_fmt == AV_PIX_FMT_YUVJ420P) {
		frame.full_range = true;
	} else {
		frame.full_range = false;
	}

	enum video_colorspace obs_cs = VIDEO_CS_DEFAULT;
	switch (src->colorspace) {
	case AVCOL_SPC_BT709:      obs_cs = VIDEO_CS_709; break;
	case AVCOL_SPC_BT470BG:
	case AVCOL_SPC_SMPTE170M:  obs_cs = VIDEO_CS_601; break;
	default:                   obs_cs = VIDEO_CS_DEFAULT; break;
	}

	if (src_fmt == AV_PIX_FMT_YUV420P || src_fmt == AV_PIX_FMT_YUVJ420P) {
		frame.format = VIDEO_FORMAT_I420;
		video_format_get_parameters_for_format(obs_cs, frame.full_range ? VIDEO_RANGE_FULL : VIDEO_RANGE_PARTIAL, frame.format, frame.color_matrix, frame.color_range_min, frame.color_range_max);
		for (int i = 0; i < 3; i++) {
			frame.data[i]     = src->data[i];
			frame.linesize[i] = src->linesize[i];
		}
		obs_source_output_video(ctx->source, &frame);
		ctx->frames_decoded++;
		return;
	} else if (src_fmt == AV_PIX_FMT_NV12) {
		frame.format = VIDEO_FORMAT_NV12;
		video_format_get_parameters_for_format(obs_cs, frame.full_range ? VIDEO_RANGE_FULL : VIDEO_RANGE_PARTIAL, frame.format, frame.color_matrix, frame.color_range_min, frame.color_range_max);
		for (int i = 0; i < 2; i++) {
			frame.data[i]     = src->data[i];
			frame.linesize[i] = src->linesize[i];
		}
		obs_source_output_video(ctx->source, &frame);
		ctx->frames_decoded++;
		return;
	}

	/* Fallback: CPU-based sws_scale conversion to BGRA for obscure formats */
	int dst_w = src_w;
	int dst_h = src_h;

	/* Recreate swscale context if dimensions or pixel format changed */
	if (!ctx->sws_ctx ||
	    ctx->last_sws_src_w != src_w ||
	    ctx->last_sws_src_h != src_h ||
	    ctx->last_sws_dst_w != dst_w ||
	    ctx->last_sws_dst_h != dst_h ||
	    ctx->last_sws_pix_fmt != src_fmt) {

		if (ctx->sws_ctx) {
			sws_freeContext(ctx->sws_ctx);
			ctx->sws_ctx = NULL;
		}

		ctx->sws_ctx = sws_getContext(
			src_w, src_h, src_fmt,
			dst_w, dst_h, AV_PIX_FMT_BGRA,
			SWS_BILINEAR, NULL, NULL, NULL);

		if (!ctx->sws_ctx) {
			blog(LOG_ERROR, "Failed to create swscale context");
			return;
		}

		ctx->last_sws_src_w = src_w;
		ctx->last_sws_src_h = src_h;
		ctx->last_sws_dst_w = dst_w;
		ctx->last_sws_dst_h = dst_h;
		ctx->last_sws_pix_fmt = src_fmt;
	}

	/* Allocate BGRA buffer */
	int bgra_size = dst_w * dst_h * 4;
	uint8_t *bgra_buf = (uint8_t *)bmalloc(bgra_size);

	uint8_t *dst_data[4]    = { bgra_buf, NULL, NULL, NULL };
	int      dst_linesize[4] = { dst_w * 4, 0, 0, 0 };

	int ret = sws_scale(ctx->sws_ctx,
	                    (const uint8_t *const *)src->data,
	                    src->linesize, 0, src_h,
	                    dst_data, dst_linesize);

	if (ret < 0) {
		blog(LOG_ERROR, "sws_scale failed: %d", ret);
		bfree(bgra_buf);
		return;
	}

	frame.format      = VIDEO_FORMAT_BGRA;
	frame.data[0]     = bgra_buf;
	frame.linesize[0] = dst_w * 4;

	obs_source_output_video(ctx->source, &frame);

	bfree(bgra_buf);
	ctx->frames_decoded++;
}

/* ── Decode thread ──────────────────────────────────────────────────────────── */
/*
 * The decode thread handles the ENTIRE lifecycle:
 *   1. Open SRT connection (may block for listener mode — that's fine, we're
 *      off the UI thread)
 *   2. Read packets, decode, push frames to OBS
 *   3. Reconnect on errors
 *
 * This ensures the OBS UI thread is NEVER blocked by avformat_open_input.
 */

static void *decode_thread_proc(void *data)
{
	struct srt_source_context *ctx = (struct srt_source_context *)data;
	int ret;

	blog(LOG_INFO, "Decode thread started for URL: %s",
	     ctx->srt_url ? ctx->srt_url : "(null)");

	ctx->thread_running = true;

	/* ── Phase 1: Open connection (may block for SRT listener) ───── */
	ctx->decoded_frame = av_frame_alloc();
	ctx->packet        = av_packet_alloc();

	if (!ctx->decoded_frame || !ctx->packet) {
		blog(LOG_ERROR, "Failed to allocate FFmpeg frames");
		ctx->thread_running = false;
		return NULL;
	}

	if (ctx->thread_stop) {
		blog(LOG_INFO, "Thread stop requested before connect");
		av_frame_free(&ctx->decoded_frame);
		av_packet_free(&ctx->packet);
		ctx->thread_running = false;
		return NULL;
	}

	blog(LOG_INFO, "Connecting to SRT stream (this may block in listener mode)...");

	ret = open_srt_stream(ctx);
	if (ret != 0) {
		blog(LOG_WARNING, "Initial SRT connection failed (ret=%d)", ret);

		/* Retry loop for initial connection */
		while (!ctx->thread_stop && ctx->reconnect_enabled) {

			ctx->reconnect_attempts++;
			blog(LOG_INFO, "Retrying connection in %dms (attempt %d)...",
			     RECONNECT_DELAY_MS,
			     ctx->reconnect_attempts);

			for (int i = 0; i < RECONNECT_DELAY_MS / 100 && !ctx->thread_stop; i++)
				sleep_ms(100);

			if (ctx->thread_stop)
				break;

			close_srt_stream(ctx);
			ret = open_srt_stream(ctx);
			if (ret == 0) {
				ctx->reconnect_attempts = 0;
				break;
			}
		}

		if (ret != 0) {
			blog(LOG_ERROR, "Could not connect to SRT stream, thread exiting");
			close_srt_stream(ctx);
			av_frame_free(&ctx->decoded_frame);
			av_packet_free(&ctx->packet);
			ctx->thread_running = false;
			return NULL;
		}
	}

	blog(LOG_INFO, "SRT stream connected, entering decode loop");

	/* ── Phase 2: Decode loop ──────────────────────────────────────── */
	while (!ctx->thread_stop) {
		/* Read a packet */
		ret = av_read_frame(ctx->fmt_ctx, ctx->packet);

		if (ret < 0) {
			if (ret == AVERROR_EOF) {
				blog(LOG_INFO, "SRT stream ended (EOF)");
			} else if (ret == AVERROR(EAGAIN)) {
				sleep_ms(10);
				continue;
			} else {
				char errbuf[256];
				av_strerror(ret, errbuf, sizeof(errbuf));
				blog(LOG_WARNING, "av_read_frame error: %s", errbuf);

				if (ctx->reconnect_enabled &&
				    !ctx->thread_stop) {
					blog(LOG_INFO, "Reconnecting in %dms (attempt %d)...",
					     RECONNECT_DELAY_MS,
					     ctx->reconnect_attempts + 1);

					for (int i = 0; i < RECONNECT_DELAY_MS / 100 && !ctx->thread_stop; i++)
						sleep_ms(100);

					if (ctx->thread_stop)
						break;

					close_srt_stream(ctx);
					if (open_srt_stream(ctx) == 0) {
						ctx->reconnect_attempts = 0;
						/* Re-alloc frames if they were freed */
						if (!ctx->packet)
							ctx->packet = av_packet_alloc();
						if (!ctx->decoded_frame)
							ctx->decoded_frame = av_frame_alloc();
						continue;
					}
					ctx->reconnect_attempts++;
				} else {
					blog(LOG_ERROR, "Max reconnects reached or stop requested");
					break;
				}
			}
			continue;
		}

		/* Only process video stream packets */
		if (ctx->packet->stream_index != ctx->video_stream_idx) {
			av_packet_unref(ctx->packet);
			continue;
		}

		/* Send packet to decoder */
		ret = avcodec_send_packet(ctx->codec_ctx, ctx->packet);
		av_packet_unref(ctx->packet);

		if (ret < 0) {
			blog(LOG_WARNING, "Error sending packet to decoder: %d", ret);
			continue;
		}

		/* Receive decoded frames */
		while (ret >= 0 && !ctx->thread_stop) {
			ret = avcodec_receive_frame(ctx->codec_ctx, ctx->decoded_frame);

			if (ret == AVERROR(EAGAIN)) {
				break;
			} else if (ret == AVERROR_EOF) {
				break;
			} else if (ret < 0) {
				blog(LOG_WARNING, "Error receiving frame: %d", ret);
				break;
			}

			output_frame_to_obs(ctx, ctx->decoded_frame);
			av_frame_unref(ctx->decoded_frame);
		}
	}

	/* ── Cleanup ───────────────────────────────────────────────────── */
	close_srt_stream(ctx);
	ctx->thread_running = false;
	blog(LOG_INFO, "Decode thread stopped");
	return NULL;
}

/* ── Stream lifecycle helpers ───────────────────────────────────────────────── */

static void stop_stream(struct srt_source_context *ctx)
{
	if (!ctx->thread_running) {
		return;
	}

	blog(LOG_INFO, "Requesting stream stop...");

	/* Signal thread to stop */
	ctx->thread_stop = true;

	/*
	 * If the thread is blocked in avformat_open_input (SRT listener waiting
	 * for a caller), we need to interrupt it. Closing the fmt_ctx's
	 * interrupt callback or the underlying socket would be ideal, but the
	 * simplest portable approach: the 5-second timeout we set in
	 * open_srt_stream will eventually cause it to return, then the thread
	 * checks thread_stop and exits.
	 *
	 * For faster shutdown, we can also try to force-close the format context
	 * from this thread, but that risks use-after-free. Instead, just wait
	 * with a generous timeout.
	 */
	pthread_join(ctx->decode_thread, NULL);
	ctx->thread_running = false;

	blog(LOG_INFO, "Stream stopped");
}

static void start_stream(struct srt_source_context *ctx)
{
	if (!ctx->srt_url || strlen(ctx->srt_url) == 0) {
		blog(LOG_WARNING, "Cannot start stream: no URL set");
		return;
	}

	if (ctx->thread_running) {
		blog(LOG_INFO, "Stream already running, skipping start");
		return;
	}

	ctx->reconnect_attempts = 0;
	ctx->frames_decoded = 0;
	ctx->frames_dropped = 0;
	ctx->first_pts_ns = 0;
	ctx->first_sys_ns = 0;

	/*
	 * Just spawn the thread — it handles open_srt_stream internally.
	 * This returns IMMEDIATELY, never blocking the OBS UI thread.
	 */
	ctx->thread_stop = false;
	int ret = pthread_create(&ctx->decode_thread, NULL,
	                         decode_thread_proc, ctx);
	if (ret != 0) {
		blog(LOG_ERROR, "Failed to create decode thread: %d", ret);
		return;
	}

	blog(LOG_INFO, "Stream thread launched for URL: %s", ctx->srt_url);
}

/* ── OBS Source Callbacks ───────────────────────────────────────────────────── */

static const char *srt_source_get_name(void *type_data)
{
	UNUSED_PARAMETER(type_data);
	return obs_module_text("SRTSource");
}

static void *srt_source_create(obs_data_t *settings, obs_source_t *source)
{
	struct srt_source_context *ctx =
		(struct srt_source_context *)bzalloc(sizeof(*ctx));

	ctx->source = source;
	ctx->video_stream_idx = -1;
	ctx->last_sws_pix_fmt = AV_PIX_FMT_NONE;

	/* 
	 * Fix for "slow motion" issue: 
	 * Tell OBS not to buffer these asynchronous video frames. 
	 * This mirrors the NDI plugin's approach to low-latency/internal sync.
	 */
	obs_source_set_async_unbuffered(source, true);

	init_ffmpeg();

	/* Apply initial settings */
	const char *url = obs_data_get_string(settings, SETTING_SRT_URL);
	if (url && strlen(url) > 0) {
		ctx->srt_url = bstrdup(url);
	}
	ctx->latency_us = (int)obs_data_get_int(settings, SETTING_LATENCY);
	ctx->reconnect_enabled = obs_data_get_bool(settings, SETTING_RECONNECT);

	blog(LOG_INFO, "SRT Source created: url=%s, latency=%dus, reconnect=%s",
	     ctx->srt_url ? ctx->srt_url : "(none)",
	     ctx->latency_us,
	     ctx->reconnect_enabled ? "yes" : "no");

	/* Start streaming immediately — don't wait for activate() */
	start_stream(ctx);

	return ctx;
}

static void srt_source_destroy(void *data)
{
	struct srt_source_context *ctx = (struct srt_source_context *)data;

	stop_stream(ctx);

	if (ctx->srt_url) {
		bfree(ctx->srt_url);
	}

	bfree(ctx);
	blog(LOG_INFO, "SRT Source destroyed");
}

static void srt_source_get_defaults(obs_data_t *settings)
{
	obs_data_set_default_string(settings, SETTING_SRT_URL,
		"srt://0.0.0.0:9000?mode=listener&latency=120000&tlpktdrop=1");
	obs_data_set_default_int(settings, SETTING_LATENCY, DEFAULT_LATENCY);
	obs_data_set_default_bool(settings, SETTING_RECONNECT, true);
}

static obs_properties_t *srt_source_get_properties(void *data)
{
	UNUSED_PARAMETER(data);

	obs_properties_t *props = obs_properties_create();

	obs_properties_add_text(props, SETTING_SRT_URL,
		obs_module_text("SRTUrl"),
		OBS_TEXT_DEFAULT);

	obs_properties_add_int_slider(props, SETTING_LATENCY,
		obs_module_text("Latency"),
		20000,    /* min: 20ms */
		5000000,  /* max: 5s */
		1000      /* step: 1ms */
	);

	obs_properties_add_bool(props, SETTING_RECONNECT,
		obs_module_text("AutoReconnect"));

	return props;
}

static void srt_source_update(void *data, obs_data_t *settings)
{
	struct srt_source_context *ctx = (struct srt_source_context *)data;

	const char *new_url = obs_data_get_string(settings, SETTING_SRT_URL);
	int new_latency = (int)obs_data_get_int(settings, SETTING_LATENCY);
	bool new_reconnect = obs_data_get_bool(settings, SETTING_RECONNECT);

	/* Check if URL actually changed */
	bool url_changed = false;
	if (ctx->srt_url && new_url) {
		url_changed = strcmp(ctx->srt_url, new_url) != 0;
	} else if (!ctx->srt_url && new_url && strlen(new_url) > 0) {
		url_changed = true;
	} else if (ctx->srt_url && (!new_url || strlen(new_url) == 0)) {
		url_changed = true;
	}

	/* Update settings */
	ctx->latency_us = new_latency;
	ctx->reconnect_enabled = new_reconnect;

	blog(LOG_INFO, "SRT Source updated: url=%s, latency=%dus, reconnect=%s, url_changed=%s",
	     new_url ? new_url : "(none)", new_latency,
	     new_reconnect ? "yes" : "no",
	     url_changed ? "yes" : "no");

	if (url_changed) {
		/* Stop existing stream */
		stop_stream(ctx);

		/* Update URL */
		if (ctx->srt_url) {
			bfree(ctx->srt_url);
			ctx->srt_url = NULL;
		}
		if (new_url && strlen(new_url) > 0) {
			ctx->srt_url = bstrdup(new_url);
		}

		/* Restart with new URL */
		start_stream(ctx);
	}
}

static void srt_source_activate(void *data)
{
	struct srt_source_context *ctx = (struct srt_source_context *)data;
	blog(LOG_INFO, "SRT Source activated (visible on program output)");

	/* Start stream if not already running (belt-and-suspenders) */
	if (!ctx->thread_running) {
		start_stream(ctx);
	}
}

static void srt_source_deactivate(void *data)
{
	UNUSED_PARAMETER(data);
	blog(LOG_INFO, "SRT Source deactivated (removed from program output)");
	/* Intentionally do NOT stop the stream here.
	 * The stream lifecycle is managed by create/update/destroy.
	 * Stopping on deactivate would kill the stream every time
	 * the user switches scenes. */
}

static void srt_source_video_tick(void *data, float seconds)
{
	UNUSED_PARAMETER(data);
	UNUSED_PARAMETER(seconds);
	/* Frame processing happens in decode thread;
	   video_tick is intentionally left minimal for performance */
}

static uint32_t srt_source_get_width(void *data)
{
	struct srt_source_context *ctx = (struct srt_source_context *)data;

	if (ctx->codec_ctx && ctx->codec_ctx->width > 0)
		return (uint32_t)ctx->codec_ctx->width;

	return 1920; /* Default */
}

static uint32_t srt_source_get_height(void *data)
{
	struct srt_source_context *ctx = (struct srt_source_context *)data;

	if (ctx->codec_ctx && ctx->codec_ctx->height > 0)
		return (uint32_t)ctx->codec_ctx->height;

	return 1080; /* Default */
}

/* ── Source info registration ───────────────────────────────────────────────── */

static struct obs_source_info srt_source_info = {
	.id             = "srt_source",
	.type           = OBS_SOURCE_TYPE_INPUT,
	.output_flags   = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_DO_NOT_DUPLICATE,
	.get_name       = srt_source_get_name,
	.create         = srt_source_create,
	.destroy        = srt_source_destroy,
	.get_defaults   = srt_source_get_defaults,
	.get_properties = srt_source_get_properties,
	.update         = srt_source_update,
	.activate       = srt_source_activate,
	.deactivate     = srt_source_deactivate,
	.video_tick     = srt_source_video_tick,
	.get_width      = srt_source_get_width,
	.get_height     = srt_source_get_height,
	.icon_type      = OBS_ICON_TYPE_CAMERA,
};

/* ── Module Entry Points ────────────────────────────────────────────────────── */

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE(PLUGIN_NAME, "en-US")

bool obs_module_load(void)
{
	obs_log(LOG_INFO, "SRT Source plugin loaded (version %s)", PLUGIN_VERSION);
	obs_register_source(&srt_source_info);
	return true;
}

void obs_module_unload(void)
{
	obs_log(LOG_INFO, "SRT Source plugin unloaded");
}
