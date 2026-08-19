import { useEffect, useState } from 'react';
import { IconMicrophone, IconSend, IconAlertTriangle, IconArrowUp } from '@tabler/icons-react';
import { Button } from '@/app/components/ui/button';
import { cn } from '@/lib/utils';
import type { VoiceRecordingController } from '@/lib/hooks/useVoiceRecording';

const RECORDING_CLASS = 'bg-destructive text-white border-destructive hover:bg-destructive/90';

// Кнопка визуально следует за пальцем, но не должна уезжать так далеко, чтобы
// наехать на полосу записи или на подсказку закрепления над собой — порог отмены
// (CANCEL_THRESHOLD) при этом считается по настоящему смещению пальца, не по этому пределу.
const VISUAL_DRAG_LIMIT = 24;

// Подсказка про закрепление видна только в начале удержания — дальше она бы просто мешала.
const LOCK_HINT_DURATION_MS = 2000;

function clampDrag(value: number): number {
  return Math.max(-VISUAL_DRAG_LIMIT, Math.min(0, value));
}

interface VoiceRecorderTriggerProps {
  recording: VoiceRecordingController;
  disabled?: boolean;
  className?: string;
}

export function VoiceRecorderTrigger({ recording, disabled, className }: VoiceRecorderTriggerProps) {
  const { mode, recorder, locked, isRecording } = recording;

  const holdActive = mode === 'HOLD' && isRecording && !locked;
  const [showLockHint, setShowLockHint] = useState(false);

  useEffect(() => {
    if (!holdActive) {
      setShowLockHint(false);
      return;
    }
    setShowLockHint(true);
    const timer = setTimeout(() => setShowLockHint(false), LOCK_HINT_DURATION_MS);
    return () => clearTimeout(timer);
  }, [holdActive]);

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
      <Button type="button" size="icon" onClick={recording.finishSend} title="Отправить запись" className={className}>
        <IconSend className="h-4 w-4" />
      </Button>
    );
  }

  if (mode === 'SILENCE') {
    return (
      <Button
        type="button"
        variant={isRecording ? 'default' : 'outline'}
        size="icon"
        disabled={disabled || recorder.status === 'requesting'}
        onClick={recording.handleSilenceClick}
        title={isRecording ? 'Идет запись — остановится сама после паузы' : 'Голосовое сообщение'}
        className={cn(isRecording && RECORDING_CLASS, isRecording && 'animate-pulse', className)}
      >
        <IconMicrophone className="h-4 w-4" />
      </Button>
    );
  }

  if (mode === 'TOGGLE') {
    return (
      <Button
        type="button"
        variant={isRecording ? 'default' : 'outline'}
        size="icon"
        disabled={disabled || recorder.status === 'requesting'}
        onClick={recording.handleToggleClick}
        title={isRecording ? 'Остановить и отправить' : 'Начать запись'}
        className={cn(isRecording && RECORDING_CLASS, isRecording && 'animate-pulse', className)}
      >
        <IconMicrophone className="h-4 w-4" />
      </Button>
    );
  }

  // ── HOLD ──
  // Обёртка — просто "рамка" под подсказку закрепления сверху; своего размера
  // не задаёт, чтобы не спорить с кнопкой за него (иначе при пустом className
  // от вызывающей стороны оба элемента остались бы без явного размера).
  return (
    <div className={cn('relative inline-flex', className)}>
      {holdActive && showLockHint && (
        <div className="pointer-events-none absolute bottom-full left-1/2 mb-1.5 flex -translate-x-1/2 flex-col items-center gap-0.5 whitespace-nowrap text-xs text-muted-foreground">
          <IconArrowUp className="h-3.5 w-3.5" />
          <span>Закрепить</span>
        </div>
      )}
      <Button
        type="button"
        variant={isRecording ? 'default' : 'outline'}
        size="icon"
        disabled={disabled || recorder.status === 'requesting'}
        onPointerDown={recording.handleHoldPointerDown}
        onPointerMove={recording.handleHoldPointerMove}
        onPointerUp={recording.handleHoldPointerUp}
        onPointerCancel={recording.finishCancel}
        title="Зажми и говори"
        className={cn(
          'touch-none select-none',
          isRecording && RECORDING_CLASS,
          isRecording && recording.cancelProgress < 1 && 'animate-pulse',
          className
        )}
        style={isRecording ? { transform: `translate(${clampDrag(recording.dragX)}px, ${clampDrag(recording.dragY)}px)` } : undefined}
      >
        <IconMicrophone className="h-4 w-4" />
      </Button>
    </div>
  );
}
