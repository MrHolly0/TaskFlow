import { useState, useRef, useEffect, useCallback } from 'react';
import { IconMicrophone, IconSend, IconSparkles, IconArrowLeft, IconPaperclip, IconAlertTriangle } from '@tabler/icons-react';
import { motion, AnimatePresence } from 'motion/react';
import { useQuickAdd, useSetActionAccepted, useApplyProposal, useRejectProposal, Proposal } from '@/lib/hooks/useAssistant';
import { ProposalCard } from '@/app/components/ProposalCard';
import { Button } from '@/app/components/ui/button';
import { Textarea } from '@/app/components/ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/app/components/ui/dialog';

type Phase = 'input' | 'recording' | 'processing' | 'confirm' | 'done';

interface QuickInputModalProps {
  open: boolean;
  onClose: () => void;
}

function isRateLimited(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'response' in error
    && (error as { response?: { status?: number } }).response?.status === 429;
}

function errorMessage(error: unknown): string {
  if (isRateLimited(error)) {
    return 'Слишком много обращений подряд — подождите минуту и попробуйте снова.';
  }
  return 'Не получилось связаться с ассистентом. Попробуйте ещё раз.';
}

export function QuickInputModal({ open, onClose }: QuickInputModalProps) {
  const quickAdd = useQuickAdd();
  const setActionAccepted = useSetActionAccepted();
  const applyProposal = useApplyProposal();
  const rejectProposal = useRejectProposal();

  const [phase, setPhase] = useState<Phase>('input');
  const [text, setText] = useState('');
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [doneLabel, setDoneLabel] = useState('Добавлено!');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const recognitionRef = useRef<any>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) {
      setPhase('input');
      setText('');
      setProposal(null);
      setError(null);
      setTimeout(() => textareaRef.current?.focus(), 100);
    }
    return () => { recognitionRef.current?.stop(); };
  }, [open]);

  const handleVoice = useCallback(() => {
    const isInTelegram = (window as any).Telegram?.WebApp?.initData;
    if (isInTelegram) {
      setError('Голосовой ввод недоступен в Telegram Mini App — введи текст вручную.');
      return;
    }

    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      setError('Голосовой ввод недоступен. Используй Chrome или Safari.');
      return;
    }

    const recognition = new SpeechRecognition();
    recognition.lang = 'ru-RU';
    recognition.interimResults = false;
    recognition.maxAlternatives = 1;
    recognitionRef.current = recognition;

    let resultReceived = false;

    recognition.onresult = (event: any) => {
      resultReceived = true;
      setText(event.results[0][0].transcript);
      setPhase('input');
      setTimeout(() => textareaRef.current?.focus(), 100);
    };
    recognition.onerror = () => {
      setError('Не удалось распознать речь. Попробуй ещё раз.');
      setPhase('input');
    };
    recognition.onend = () => { if (!resultReceived) setPhase('input'); };

    setPhase('recording');
    recognition.start();
  }, []);

  const closeWithSuccess = (label: string) => {
    setDoneLabel(label);
    setPhase('done');
    setTimeout(() => onClose(), 1500);
  };

  const send = (payload: { text?: string; file?: File }) => {
    setPhase('processing');
    setError(null);
    quickAdd.mutate(payload, {
      onSuccess: (result) => {
        if (result.applied) {
          closeWithSuccess('Добавлено!');
        } else {
          setProposal(result.proposal);
          setPhase('confirm');
        }
      },
      onError: (err) => {
        setError(errorMessage(err));
        setPhase('input');
      },
    });
  };

  const handleSubmit = () => {
    if (!text.trim()) return;
    send({ text: text.trim() });
  };

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    e.target.value = '';
    send({ file });
  };

  const toggleAction = (ordinal: number, accepted: boolean) => {
    if (!proposal?.id) return;
    setActionAccepted.mutate(
      { proposalId: proposal.id, ordinal, accepted },
      { onSuccess: (updated) => setProposal(updated) }
    );
  };

  const applyCurrent = () => {
    if (!proposal?.id) return;
    applyProposal.mutate(proposal.id, {
      onSuccess: () => closeWithSuccess('Применено!'),
      onError: (err) => setError(errorMessage(err)),
    });
  };

  const rejectCurrent = () => {
    if (!proposal?.id) return;
    rejectProposal.mutate(proposal.id, {
      onSuccess: () => { setPhase('input'); setProposal(null); },
      onError: (err) => setError(errorMessage(err)),
    });
  };

  const handleBack = () => { setPhase('input'); setProposal(null); setError(null); };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSubmit();
  };

  const handleClose = () => { recognitionRef.current?.stop(); onClose(); };

  return (
    <Dialog open={open} onOpenChange={(o: boolean) => !o && handleClose()}>
      <DialogContent className="sm:max-w-md w-full max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <IconSparkles className="h-5 w-5 text-primary" />
            Что записать?
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <AnimatePresence mode="wait">
            {phase === 'input' && (
              <motion.div key="input" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="space-y-3">
                <Textarea
                  ref={textareaRef}
                  value={text}
                  onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => { setText(e.target.value); setError(null); }}
                  onKeyDown={handleKeyDown}
                  placeholder="Погулять с собакой, купить хлеба, позвонить маме..."
                  className="resize-none min-h-[100px] text-base"
                />
                <p className="text-xs text-muted-foreground">
                  Ассистент применит сразу, если это только новые задачи — иначе спросит подтверждения
                </p>
                {error && (
                  <div className="text-sm text-destructive bg-destructive/10 p-3 rounded-lg flex items-start gap-2">
                    <IconAlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
                    <span>{error}</span>
                  </div>
                )}
                <div className="flex gap-2">
                  <Button variant="outline" onClick={handleVoice} className="gap-2 h-11">
                    <IconMicrophone className="h-4 w-4" />
                    Голос
                  </Button>
                  <Button variant="outline" onClick={() => fileInputRef.current?.click()} className="gap-2 h-11">
                    <IconPaperclip className="h-4 w-4" />
                    Файл
                  </Button>
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="audio/*"
                    className="hidden"
                    onChange={handleFileSelect}
                  />
                  <Button onClick={handleSubmit} disabled={!text.trim()} className="flex-1 gap-2 h-11">
                    <IconSend className="h-4 w-4" />
                    Отправить
                    <span className="text-xs opacity-60 ml-1">⌘↵</span>
                  </Button>
                </div>
              </motion.div>
            )}

            {phase === 'recording' && (
              <motion.div key="recording" initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.95 }} className="flex flex-col items-center justify-center py-8 space-y-4">
                <div className="relative">
                  <motion.div animate={{ scale: [1, 1.3, 1] }} transition={{ duration: 1, repeat: Infinity }} className="absolute inset-0 bg-red-500/20 rounded-full" />
                  <div className="relative w-16 h-16 bg-red-500 rounded-full flex items-center justify-center">
                    <IconMicrophone className="h-7 w-7 text-white" />
                  </div>
                </div>
                <p className="text-muted-foreground text-sm">Говори...</p>
                <div className="flex gap-1 items-end h-6">
                  {[1, 2, 3, 4, 5].map((i) => (
                    <motion.div key={i} className="w-1 bg-red-400 rounded-full" animate={{ height: ['8px', '20px', '8px'] }} transition={{ duration: 0.8, repeat: Infinity, delay: i * 0.1 }} />
                  ))}
                </div>
              </motion.div>
            )}

            {phase === 'processing' && (
              <motion.div key="processing" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col items-center justify-center py-8 space-y-3">
                <motion.div animate={{ rotate: 360 }} transition={{ duration: 1, repeat: Infinity, ease: 'linear' }} className="w-10 h-10 border-2 border-primary/20 border-t-primary rounded-full" />
                <p className="text-muted-foreground text-sm">Обрабатываю обращение…</p>
              </motion.div>
            )}

            {phase === 'confirm' && proposal && (
              <motion.div key="confirm" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="space-y-3">
                <p className="text-xs text-muted-foreground">
                  Здесь есть изменения к существующим задачам — проверь перед подтверждением.
                </p>

                {error && (
                  <div className="text-sm text-destructive bg-destructive/10 p-3 rounded-lg flex items-start gap-2">
                    <IconAlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
                    <span>{error}</span>
                  </div>
                )}

                <ProposalCard
                  proposal={proposal}
                  onToggle={toggleAction}
                  onApply={applyCurrent}
                  onReject={rejectCurrent}
                  applying={applyProposal.isPending}
                  rejecting={rejectProposal.isPending}
                />

                <Button variant="outline" onClick={handleBack} className="gap-2 h-11 w-full">
                  <IconArrowLeft className="h-4 w-4" />
                  Назад
                </Button>
              </motion.div>
            )}

            {phase === 'done' && (
              <motion.div key="done" initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} className="flex flex-col items-center justify-center py-6 space-y-4">
                <motion.div initial={{ scale: 0 }} animate={{ scale: 1 }} transition={{ type: 'spring', duration: 0.5 }} className="w-14 h-14 bg-green-500 rounded-full flex items-center justify-center">
                  <IconSparkles className="h-7 w-7 text-white" />
                </motion.div>
                <p className="font-medium">{doneLabel}</p>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </DialogContent>
    </Dialog>
  );
}
