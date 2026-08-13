import { useEffect, useRef, useState } from 'react';
import { IconMicrophone, IconTrash, IconSend, IconLock, IconAlertTriangle } from '@tabler/icons-react';
import { useVoiceRecorder } from '@/lib/hooks/useVoiceRecorder';
import { Button } from '@/app/components/ui/button';
import { cn } from '@/lib/utils';
import type { VoiceInputMode } from '@/lib/hooks/useSettings';

const CANCEL_THRESHOLD = 80;
const LOCK_THRESHOLD = 60;

interface VoiceRecorderButtonProps {
  mode: VoiceInputMode;
  onRecorded: (file: File) => void;
  disabled?: boolean;
  className?: string;
}

export function VoiceRecorderButton({ mode, onRecorded, disabled, className }: VoiceRecorderButtonProps) {
  const recorder = useVoiceRecorder();
  const [locked, setLocked] = useState(false);
  const [dragX, setDragX] = useState(0);
  const [dragY, setDragY] = useState(0);
  const dragRef = useRef({ x: 0, y: 0 });
  const lockedRef = useRef(false);
  const startPointRef = useRef<{ x: number; y: number } | null>(null);
  const wrapperRef = useRef<HTMLDivElement>(null);

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

  // ── Закрепление: клик мимо кнопки удаляет запись, клик по кнопке отправляет ──
  useEffect(() => {
    if (!locked) return;
    const onDocumentPointerDown = (e: PointerEvent) => {
      if (wrapperRef.current && wrapperRef.current.contains(e.target as Node)) return;
      finishCancel();
    };
    document.addEventListener('pointerdown', onDocumentPointerDown);
    return () => document.removeEventListener('pointerdown', onDocumentPointerDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locked]);

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

  if (recorder.status === 'error') {
    return (
      <div className="flex items-center gap-1.5 text-xs text-destructive">
        <IconAlertTriangle className="h-4 w-4 flex-shrink-0" />
        <span className="min-w-0 break-words">{recorder.error}</span>
      </div>
    );
  }

  if (locked) {
    return (
      <div ref={wrapperRef}>
        <Button type="button" size="icon" onClick={finishSend} title="Отправить запись">
          <IconSend className="h-4 w-4" />
        </Button>
      </div>
    );
  }

  if (mode === 'SILENCE') {
    return (
      <Button
        type="button"
        variant={recorder.status === 'recording' ? 'default' : 'outline'}
        size="icon"
        disabled={disabled || recorder.status === 'requesting'}
        onClick={handleSilenceClick}
        title={recorder.status === 'recording' ? 'Идёт запись — остановится сама после паузы' : 'Голосовое сообщение'}
        className={cn(recorder.status === 'recording' && 'animate-pulse', className)}
      >
        <IconMicrophone className="h-4 w-4" />
      </Button>
    );
  }

  if (mode === 'TOGGLE') {
    return (
      <Button
        type="button"
        variant={recorder.status === 'recording' ? 'default' : 'outline'}
        size="icon"
        disabled={disabled || recorder.status === 'requesting'}
        onClick={handleToggleClick}
        title={recorder.status === 'recording' ? 'Остановить и отправить' : 'Начать запись'}
        className={cn(recorder.status === 'recording' && 'animate-pulse', className)}
      >
        <IconMicrophone className="h-4 w-4" />
      </Button>
    );
  }

  // ── HOLD ──
  const cancelProgress = Math.min(1, -dragX / CANCEL_THRESHOLD);
  const isRecording = recorder.status === 'recording';

  return (
    <div ref={wrapperRef} className="relative flex items-center">
      {isRecording && (
        <div
          className="pointer-events-none absolute right-full mr-2 flex items-center gap-1.5 whitespace-nowrap text-xs"
          style={{ transform: `translateX(${Math.max(dragX, -CANCEL_THRESHOLD - 20)}px)` }}
        >
          <IconTrash
            className={cn('h-4 w-4 transition-colors', cancelProgress >= 1 ? 'text-destructive' : 'text-muted-foreground')}
          />
          {dragY < -10 && (
            <span className="flex items-center gap-1 text-muted-foreground">
              <IconLock className="h-3 w-3" /> вверх — закрепить
            </span>
          )}
        </div>
      )}
      <Button
        type="button"
        variant={isRecording ? 'default' : 'outline'}
        size="icon"
        disabled={disabled || recorder.status === 'requesting'}
        onPointerDown={handleHoldPointerDown}
        onPointerMove={handleHoldPointerMove}
        onPointerUp={handleHoldPointerUp}
        onPointerCancel={finishCancel}
        title="Зажми и говори"
        className={cn('touch-none select-none', isRecording && cancelProgress < 1 && 'animate-pulse', className)}
        style={isRecording ? { transform: `translate(${dragX}px, ${dragY}px)` } : undefined}
      >
        <IconMicrophone className="h-4 w-4" />
      </Button>
    </div>
  );
}
