import { useEffect, useRef, useState } from 'react';
import { useVoiceRecorder } from './useVoiceRecorder';
import type { VoiceInputMode } from './useSettings';

const CANCEL_THRESHOLD = 80;
const LOCK_THRESHOLD = 60;

export function useVoiceRecording(mode: VoiceInputMode, onRecorded: (file: File) => void) {
  const recorder = useVoiceRecorder();
  const [locked, setLocked] = useState(false);
  const [dragX, setDragX] = useState(0);
  const [dragY, setDragY] = useState(0);
  const dragRef = useRef({ x: 0, y: 0 });
  const lockedRef = useRef(false);
  const startPointRef = useRef<{ x: number; y: number } | null>(null);
  const [elapsedMs, setElapsedMs] = useState(0);
  const startedAtRef = useRef<number | null>(null);

  const isRecording = recorder.status === 'recording';

  useEffect(() => {
    if (!isRecording) {
      startedAtRef.current = null;
      setElapsedMs(0);
      return;
    }
    startedAtRef.current = Date.now();
    setElapsedMs(0);
    const id = window.setInterval(() => {
      setElapsedMs(Date.now() - (startedAtRef.current ?? Date.now()));
    }, 250);
    return () => window.clearInterval(id);
  }, [isRecording]);

  const reset = () => {
    setLocked(false);
    lockedRef.current = false;
    setDragX(0);
    setDragY(0);
    dragRef.current = { x: 0, y: 0 };
    startPointRef.current = null;
  };

  const finishSend = async () => {
    const file = await recorder.stop();
    reset();
    if (file) onRecorded(file);
  };

  const finishCancel = () => {
    recorder.cancel();
    reset();
  };

  // ── По тишине ──
  const handleSilenceClick = async () => {
    if (recorder.status === 'recording') {
      finishCancel();
      return;
    }
    await recorder.start({
      autoStopOnSilence: true,
      onAutoStop: (file) => {
        reset();
        onRecorded(file);
      },
    });
  };

  // ── Переключением ──
  const handleToggleClick = async () => {
    if (recorder.status === 'recording') {
      await finishSend();
      return;
    }
    await recorder.start();
  };

  // ── Удержанием ──
  const handleHoldPointerDown = async (e: React.PointerEvent<HTMLButtonElement>) => {
    if (locked) return; // после закрепления клик обрабатывается через onClick, не здесь
    e.preventDefault();
    startPointRef.current = { x: e.clientX, y: e.clientY };
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    await recorder.start();
  };

  const handleHoldPointerMove = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (!startPointRef.current || recorder.status !== 'recording' || lockedRef.current) return;
    const dx = Math.min(0, e.clientX - startPointRef.current.x);
    const dy = Math.min(0, e.clientY - startPointRef.current.y);
    dragRef.current = { x: dx, y: dy };
    setDragX(dx);
    setDragY(dy);

    if (-dy > LOCK_THRESHOLD) {
      lockedRef.current = true;
      setLocked(true);
    }
  };

  const handleHoldPointerUp = () => {
    if (!startPointRef.current) return;
    startPointRef.current = null;
    if (lockedRef.current) return; // палец поднят после закрепления — запись продолжается сама
    if (-dragRef.current.x > CANCEL_THRESHOLD) {
      finishCancel();
    } else {
      finishSend();
    }
  };

  return {
    mode,
    recorder,
    isRecording,
    elapsedMs,
    locked,
    dragX,
    dragY,
    cancelProgress: Math.min(1, -dragX / CANCEL_THRESHOLD),
    handleSilenceClick,
    handleToggleClick,
    handleHoldPointerDown,
    handleHoldPointerMove,
    handleHoldPointerUp,
    finishSend,
    finishCancel,
  };
}

export type VoiceRecordingController = ReturnType<typeof useVoiceRecording>;
