from __future__ import annotations

import array
import math
from dataclasses import dataclass

# int16 满刻度。dBFS = 20 * log10(|x| / FULL_SCALE)
_FULL_SCALE = 32768.0
_INT16_MIN = -32768
_INT16_MAX = 32767
_MIN_APPLY_DB = 0.5
_DEFAULT_WINDOW_MS = 200

DEFAULT_RMS_THRESHOLD_DBFS = -35.0
DEFAULT_PEAK_THRESHOLD_DBFS = -25.0
DEFAULT_NOISE_GATE_DBFS = -50.0
DEFAULT_TARGET_PEAK_DBFS = -3.0
DEFAULT_MAX_GAIN_DB = 12.0


@dataclass(frozen=True, slots=True)
class AsrGainConfig:
    """过轻人声增益：仅抬升「像语音但太轻」的片段，不抬底噪。"""

    enabled: bool = False
    rms_threshold_dbfs: float = DEFAULT_RMS_THRESHOLD_DBFS
    peak_threshold_dbfs: float = DEFAULT_PEAK_THRESHOLD_DBFS
    noise_gate_dbfs: float = DEFAULT_NOISE_GATE_DBFS
    target_peak_dbfs: float = DEFAULT_TARGET_PEAK_DBFS
    max_gain_db: float = DEFAULT_MAX_GAIN_DB

    @classmethod
    def from_settings(cls, settings: object) -> AsrGainConfig:
        return cls(
            enabled=bool(getattr(settings, "asr_gain_enabled", False)),
            rms_threshold_dbfs=float(
                getattr(
                    settings,
                    "asr_gain_rms_threshold_dbfs",
                    DEFAULT_RMS_THRESHOLD_DBFS,
                )
            ),
            peak_threshold_dbfs=float(
                getattr(
                    settings,
                    "asr_gain_peak_threshold_dbfs",
                    DEFAULT_PEAK_THRESHOLD_DBFS,
                )
            ),
            noise_gate_dbfs=float(
                getattr(
                    settings,
                    "asr_gain_noise_gate_dbfs",
                    DEFAULT_NOISE_GATE_DBFS,
                )
            ),
            target_peak_dbfs=float(
                getattr(
                    settings,
                    "asr_gain_target_peak_dbfs",
                    DEFAULT_TARGET_PEAK_DBFS,
                )
            ),
            max_gain_db=float(
                getattr(settings, "asr_gain_max_db", DEFAULT_MAX_GAIN_DB)
            ),
        )


def pcm_levels_dbfs(pcm: bytes) -> tuple[float, float]:
    return sample_levels_dbfs(_pcm_to_samples(pcm))


def sample_levels_dbfs(samples: array.array) -> tuple[float, float]:
    """返回 (rms_dbfs, peak_dbfs)；无信号时为 -inf。"""
    if not samples:
        return -math.inf, -math.inf
    peak = 0
    sum_sq = 0.0
    for sample in samples:
        magnitude = sample if sample >= 0 else -sample
        if magnitude > peak:
            peak = magnitude
        sum_sq += sample * sample
    rms = math.sqrt(sum_sq / len(samples))
    return _to_dbfs(rms), _to_dbfs(float(peak))


def decide_gain_db(samples: array.array, config: AsrGainConfig) -> float:
    """最稳触发：底噪以上、RMS 与峰值都过轻，才按峰值目标抬升并封顶。"""
    if not config.enabled or not samples:
        return 0.0
    rms_dbfs, peak_dbfs = sample_levels_dbfs(samples)
    if not math.isfinite(rms_dbfs) or rms_dbfs <= config.noise_gate_dbfs:
        return 0.0
    if rms_dbfs >= config.rms_threshold_dbfs:
        return 0.0
    if not math.isfinite(peak_dbfs) or peak_dbfs >= config.peak_threshold_dbfs:
        return 0.0
    gain_db = min(config.max_gain_db, config.target_peak_dbfs - peak_dbfs)
    if gain_db < _MIN_APPLY_DB:
        return 0.0
    return gain_db


def apply_gain_db(pcm: bytes, gain_db: float) -> bytes:
    if gain_db <= 0 or len(pcm) < 2:
        return pcm
    factor = 10 ** (gain_db / 20.0)
    samples = _pcm_to_samples(pcm)
    boosted = array.array("h")
    for sample in samples:
        value = int(round(sample * factor))
        if value > _INT16_MAX:
            value = _INT16_MAX
        elif value < _INT16_MIN:
            value = _INT16_MIN
        boosted.append(value)
    return boosted.tobytes()


def boost_pcm(pcm: bytes, config: AsrGainConfig) -> tuple[bytes, float]:
    """对整段 PCM 一次性测电平并增益。返回 (pcm, 实际增益 dB)。"""
    if not config.enabled or len(pcm) < 2:
        return pcm, 0.0
    gain_db = decide_gain_db(_pcm_to_samples(pcm), config)
    if gain_db <= 0:
        return pcm, 0.0
    return apply_gain_db(pcm, gain_db), gain_db


class RollingAsrGain:
    """流式分包：用约 200ms 滑窗测电平，增益只作用在当前包。"""

    def __init__(
        self,
        config: AsrGainConfig,
        *,
        sample_rate: int,
        window_ms: int = _DEFAULT_WINDOW_MS,
    ) -> None:
        self._config = config
        self._window_samples = max(int(sample_rate * window_ms / 1000), 1)
        self._window = array.array("h")

    def process(self, pcm: bytes) -> tuple[bytes, float]:
        if not self._config.enabled or len(pcm) < 2:
            return pcm, 0.0
        chunk = _pcm_to_samples(pcm)
        self._window.extend(chunk)
        overflow = len(self._window) - self._window_samples
        if overflow > 0:
            del self._window[:overflow]
        gain_db = decide_gain_db(self._window, self._config)
        if gain_db <= 0:
            return pcm, 0.0
        return apply_gain_db(pcm, gain_db), gain_db


def _pcm_to_samples(pcm: bytes) -> array.array:
    samples = array.array("h")
    aligned = len(pcm) - (len(pcm) % 2)
    if aligned:
        samples.frombytes(pcm[:aligned])
    return samples


def _to_dbfs(amplitude: float) -> float:
    if amplitude <= 0:
        return -math.inf
    return 20.0 * math.log10(amplitude / _FULL_SCALE)
