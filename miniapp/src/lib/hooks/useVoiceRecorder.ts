import { useCallback, useRef, useState } from 'react';

// Не дать случайно улететь в Whisper (и оплатить) десять минут тишины,
// если что-то в UI не остановило запись вовремя.
const MAX_DURATION_MS = 3 * 60 * 1000;
const SILENCE_RMS_THRESHOLD = 0.02;
const SILENCE_DURATION_MS = 1500;
const SILENCE_CHECK_INTERVAL_MS = 100;

export type RecorderStatus = 'idle' | 'requesting' | 'recording' | 'error';

interface StartOptions {
  autoStopOnSilence?: boolean;
  onAutoStop?: (file: File) => void;
}

function extensionFor(mimeType: string): string {
  if (mimeType.includes('mp4')) return 'mp4';
  if (mimeType.includes('ogg')) return 'ogg';
  return 'webm';
}

export function useVoiceRecorder() {
  const [status, setStatus] = useState<RecorderStatus>('idle');
  const [error, setError] = useState<string | null>(null);

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const audioContextRef = useRef<AudioContext | null>(null);
  const silenceTimerRef = useRef<number | null>(null);
  const silenceCheckIntervalRef = useRef<number | null>(null);
  const maxDurationTimerRef = useRef<number | null>(null);
  const resolveStopRef = useRef<((file: File | null) => void) | null>(null);
  const cancelledRef = useRef(false);
  const onAutoStopRef = useRef<StartOptions['onAutoStop']>(undefined);

  const cleanup = useCallback(() => {
    if (silenceCheckIntervalRef.current) {
      window.clearInterval(silenceCheckIntervalRef.current);
      silenceCheckIntervalRef.current = null;
    }
    if (silenceTimerRef.current) {
      window.clearTimeout(silenceTimerRef.current);
      silenceTimerRef.current = null;
    }
    if (maxDurationTimerRef.current) {
      window.clearTimeout(maxDurationTimerRef.current);
      maxDurationTimerRef.current = null;
    }
    audioContextRef.current?.close().catch(() => {});
    audioContextRef.current = null;
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    mediaRecorderRef.current = null;
  }, []);

  const finalize = useCallback((send: boolean) => {
    const recorder = mediaRecorderRef.current;
    if (!recorder || recorder.state === 'inactive') return;
    cancelledRef.current = !send;
    recorder.stop();
  }, []);

  const start = useCallback(async (options: StartOptions = {}) => {
    setError(null);
    setStatus('requesting');
    onAutoStopRef.current = options.onAutoStop;
    cancelledRef.current = false;
    chunksRef.current = [];

    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      setStatus('error');
      setError('Нет доступа к микрофону — разреши доступ в настройках браузера и попробуй ещё раз.');
      return;
    }
    streamRef.current = stream;

    const mimeType = MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' : '';
    const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
    mediaRecorderRef.current = recorder;

    recorder.ondataavailable = (e) => {
      if (e.data.size > 0) chunksRef.current.push(e.data);
    };

    recorder.onstop = () => {
      const blob = new Blob(chunksRef.current, { type: recorder.mimeType || 'audio/webm' });
      const wasCancelled = cancelledRef.current;
      cleanup();
      setStatus('idle');

      if (wasCancelled || blob.size === 0) {
        resolveStopRef.current?.(null);
        resolveStopRef.current = null;
        return;
      }

      const file = new File([blob], `voice.${extensionFor(blob.type)}`, { type: blob.type });
      if (resolveStopRef.current) {
        resolveStopRef.current(file);
        resolveStopRef.current = null;
      } else {
        // Остановлено по тишине — явного stop() никто не ждал, отдаём через колбэк.
        onAutoStopRef.current?.(file);
      }
    };

    recorder.start();
    setStatus('recording');

    maxDurationTimerRef.current = window.setTimeout(() => finalize(true), MAX_DURATION_MS);

    if (options.autoStopOnSilence) {
      const AudioContextCtor = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      const audioContext = new AudioContextCtor();
      audioContextRef.current = audioContext;
      const source = audioContext.createMediaStreamSource(stream);
      const analyser = audioContext.createAnalyser();
      analyser.fftSize = 2048;
      source.connect(analyser);
      const data = new Uint8Array(analyser.fftSize);

      silenceCheckIntervalRef.current = window.setInterval(() => {
        analyser.getByteTimeDomainData(data);
        let sumSquares = 0;
        for (let i = 0; i < data.length; i++) {
          const normalized = (data[i] - 128) / 128;
          sumSquares += normalized * normalized;
        }
        const rms = Math.sqrt(sumSquares / data.length);

        if (rms < SILENCE_RMS_THRESHOLD) {
          if (!silenceTimerRef.current) {
            silenceTimerRef.current = window.setTimeout(() => finalize(true), SILENCE_DURATION_MS);
          }
        } else if (silenceTimerRef.current) {
          window.clearTimeout(silenceTimerRef.current);
          silenceTimerRef.current = null;
        }
      }, SILENCE_CHECK_INTERVAL_MS);
    }
  }, [cleanup, finalize]);

  const stop = useCallback((): Promise<File | null> => {
    return new Promise((resolve) => {
      resolveStopRef.current = resolve;
      finalize(true);
    });
  }, [finalize]);

  const cancel = useCallback(() => {
    finalize(false);
  }, [finalize]);

  return { status, error, start, stop, cancel };
}
