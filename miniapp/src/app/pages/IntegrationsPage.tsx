import { useState, useCallback } from 'react';
import { toast } from 'sonner';
import { IconBrandTelegram, IconMail, IconCheck } from '@tabler/icons-react';
import { Card } from '@/app/components/ui/card';
import { Badge } from '@/app/components/ui/badge';
import { Button, buttonVariants } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { Separator } from '@/app/components/ui/separator';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/app/components/ui/alert-dialog';
import { cn } from '@/lib/utils';
import { EmailCodeStep } from '@/app/components/EmailCodeStep';
import { TelegramLoginWidget } from '@/app/components/TelegramLoginWidget';
import { MergeConflictDialog } from '@/app/components/MergeConflictDialog';
import { isTelegramWebApp } from '@/lib/auth';
import {
  useIdentities,
  useRequestBindEmailCode,
  useConfirmBindEmail,
  useBindTelegram,
  useMergeAccounts,
  useUnbindIdentity,
  isMergeConflict,
  type AccountTransferResult,
  type IdentityBindResponse,
  type IdentityProvider,
  type MergeConflictResponse,
} from '@/lib/hooks/useIdentities';

const EMPTY_TRANSFER: AccountTransferResult = {
  tasks: 0, groups: 0, tags: 0, notifications: 0, auditEvents: 0, proposals: 0, identities: 0,
};

function mergeToastMessage(result: AccountTransferResult): string {
  const parts: string[] = [];
  if (result.tasks > 0) parts.push(`${result.tasks} задач`);
  if (result.groups > 0) parts.push(`${result.groups} групп`);
  if (result.tags > 0) parts.push(`${result.tags} меток`);
  if (parts.length === 0) return 'Способ входа подключён — данные с ним переносить не пришлось.';
  return `Перенесли с прежней учётки: ${parts.join(', ')}.`;
}

// Доказательство владения идентификатором и согласие на слияние двух
// учёток — разные вещи. При 409 от confirm/telegram показываем диалог
// с числами и ждём явного «Перенести»; «Отмена» не должна молча оставить
// способ входа непривязанным — сообщаем об этом прямо и даём начать заново.
function useMergeFlow(onMerged: (result: IdentityBindResponse) => void, onResolved: () => void) {
  const [conflict, setConflict] = useState<MergeConflictResponse | null>(null);
  const mergeAccounts = useMergeAccounts();

  const catchConflict = useCallback((err: unknown): boolean => {
    if (isMergeConflict(err)) {
      setConflict(err.response.data);
      return true;
    }
    return false;
  }, []);

  const confirm = () => {
    if (!conflict) return;
    mergeAccounts.mutate(conflict.mergeToken, {
      onSuccess: (result) => {
        setConflict(null);
        onMerged(result);
        onResolved();
      },
      onError: () => {
        toast.error('Не получилось перенести данные, попробуйте ещё раз');
      },
    });
  };

  const cancel = () => {
    setConflict(null);
    toast.info('Способ входа не подключён. Можно начать заново.');
    onResolved();
  };

  return { conflict, merging: mergeAccounts.isPending, catchConflict, confirm, cancel };
}

function IntegrationsHeader() {
  return (
    <div className="space-y-1">
      <h1 className="text-2xl font-semibold">Интеграции</h1>
      <p className="text-sm text-muted-foreground">
        Способы входа в аккаунт. Привяжи хотя бы два, чтобы не потерять доступ, если один перестанет работать.
      </p>
    </div>
  );
}

function SectionSkeleton() {
  return (
    <div className="flex items-center gap-3" aria-hidden="true">
      <div className="h-10 w-10 shrink-0 rounded-lg bg-muted animate-pulse" />
      <div className="flex flex-col gap-1.5">
        <div className="h-3.5 w-20 rounded bg-muted animate-pulse" />
        <div className="h-3 w-28 rounded bg-muted animate-pulse" />
      </div>
    </div>
  );
}

export function IntegrationsPage() {
  const { data: identities, isLoading, isError, refetch } = useIdentities();
  const unbind = useUnbindIdentity();

  // Элементы управления, меняющие состояние учётки, не рисуются, пока
  // состояние неизвестно: пустой identities по умолчанию неотличим от
  // «загрузка ещё идёт» или «запрос упал», а разделы это разные вещи —
  // «ничего не подключено» и «не знаем, что подключено».
  if (isLoading) {
    return (
      <div className="max-w-2xl mx-auto space-y-6">
        <IntegrationsHeader />
        <Card className="p-6 flex flex-col gap-6">
          <SectionSkeleton />
          <Separator />
          <SectionSkeleton />
        </Card>
      </div>
    );
  }

  if (isError || !identities) {
    return (
      <div className="max-w-2xl mx-auto space-y-6">
        <IntegrationsHeader />
        <Card className="p-6">
          <div className="flex flex-col items-center gap-3 py-6 text-center">
            <p className="text-sm text-muted-foreground">Не удалось загрузить способы входа.</p>
            <Button variant="outline" size="sm" onClick={() => refetch()}>Повторить</Button>
          </div>
        </Card>
      </div>
    );
  }

  const telegram = identities.find((i) => i.provider === 'TELEGRAM');
  const email = identities.find((i) => i.provider === 'EMAIL');
  const canUnbind = identities.length > 1;

  const handleUnbind = (provider: IdentityProvider) => {
    unbind.mutate(provider, {
      onError: (err: any) => {
        const detail = err?.response?.data?.detail;
        toast.error(detail || 'Не получилось отвязать способ входа');
      },
    });
  };

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <IntegrationsHeader />

      <Card className="p-6 flex flex-col gap-6">
        {/* Не предупреждение (для этого есть диалог согласия в момент
            привязки) — единственное место, где человек может узнать про
            слияние учёток заранее, до того как оно случится. */}
        <p className="text-xs text-muted-foreground">
          Один аккаунт — несколько способов входа. Если на почте или в Telegram уже была отдельная
          учётка с задачами, при подключении мы предложим перенести их сюда, в одну.
        </p>

        <TelegramSection
          connected={Boolean(telegram)}
          canUnbind={canUnbind}
          onUnbind={() => handleUnbind('TELEGRAM')}
          unbinding={unbind.isPending && unbind.variables === 'TELEGRAM'}
        />

        <Separator />

        <EmailSection
          connected={Boolean(email)}
          externalId={email?.externalId}
          canUnbind={canUnbind}
          onUnbind={() => handleUnbind('EMAIL')}
          unbinding={unbind.isPending && unbind.variables === 'EMAIL'}
        />
      </Card>
    </div>
  );
}

function TelegramSection({
  connected,
  canUnbind,
  onUnbind,
  unbinding,
}: {
  connected: boolean;
  canUnbind: boolean;
  onUnbind: () => void;
  unbinding: boolean;
}) {
  const bindTelegram = useBindTelegram();
  const [widgetAvailable, setWidgetAvailable] = useState<boolean | null>(null);
  const merge = useMergeFlow(
    (result) => toast.success(mergeToastMessage(result.mergedFrom ?? EMPTY_TRANSFER)),
    () => {},
  );

  // useCallback с пустыми зависимостями — тот же приём, что на AuthPage:
  // без него виджет пересоздаёт свой script при каждом ре-рендере страницы.
  // merge.catchConflict тоже стабилен (useCallback([]) внутри useMergeFlow).
  const handleAuth = useCallback((widgetUser: Record<string, string | number>) => {
    const fields: Record<string, string> = {};
    for (const [key, value] of Object.entries(widgetUser)) {
      fields[key] = String(value);
    }
    bindTelegram.mutate(fields, {
      onSuccess: (result) => {
        toast.success(mergeToastMessage(result.mergedFrom ?? EMPTY_TRANSFER));
      },
      onError: (err) => {
        if (merge.catchConflict(err)) return;
        toast.error((err as any)?.response?.data?.detail || 'Не получилось привязать Telegram');
      },
    });
  }, [bindTelegram, merge.catchConflict]);

  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-[#0088cc]/10">
            <IconBrandTelegram className="h-5 w-5" style={{ color: '#0088cc' }} />
          </div>
          <div>
            <p className="text-sm font-medium">Telegram</p>
            {connected ? (
              <Badge variant="secondary" className="gap-1 mt-0.5">
                <IconCheck className="h-3 w-3" />
                Подключён
              </Badge>
            ) : (
              <p className="text-xs text-muted-foreground">Не подключён</p>
            )}
          </div>
        </div>

        {connected && (
          <AlertDialog>
            {/* Без asChild: Button — обычная функция без forwardRef. */}
            <AlertDialogTrigger
              className={cn(buttonVariants({ variant: 'outline', size: 'sm' }))}
              disabled={!canUnbind || unbinding}
              title={!canUnbind ? 'Это единственный способ входа' : undefined}
            >
              {unbinding ? 'Отвязываем...' : 'Отвязать'}
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Отвязать Telegram?</AlertDialogTitle>
                <AlertDialogDescription>
                  Больше нельзя будет войти через Telegram. Задачи и данные останутся — доступ через оставшийся способ входа не изменится.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Отмена</AlertDialogCancel>
                <AlertDialogAction onClick={onUnbind}>Отвязать</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        )}
      </div>

      {!connected && !isTelegramWebApp() && widgetAvailable !== false && (
        <TelegramLoginWidget onAuth={handleAuth} onLoaded={setWidgetAvailable} />
      )}
      {!connected && !isTelegramWebApp() && widgetAvailable === false && (
        <p className="text-xs text-muted-foreground">
          Кнопка Telegram сейчас недоступна. Попробуйте зайти на сайт через VPN.
        </p>
      )}

      <MergeConflictDialog
        conflict={merge.conflict}
        merging={merge.merging}
        onConfirm={merge.confirm}
        onCancel={merge.cancel}
      />
    </section>
  );
}

function EmailSection({
  connected,
  externalId,
  canUnbind,
  onUnbind,
  unbinding,
}: {
  connected: boolean;
  externalId?: string;
  canUnbind: boolean;
  onUnbind: () => void;
  unbinding: boolean;
}) {
  const [step, setStep] = useState<'idle' | 'email' | 'code'>('idle');
  const [email, setEmail] = useState('');
  const requestCode = useRequestBindEmailCode();
  const confirmEmail = useConfirmBindEmail();
  const merge = useMergeFlow(
    (result) => toast.success(mergeToastMessage(result.mergedFrom ?? EMPTY_TRANSFER)),
    () => setStep('idle'),
  );

  const handleSubmitEmail = async (e: React.FormEvent) => {
    e.preventDefault();
    if (requestCode.isPending || !email) return;
    try {
      await requestCode.mutateAsync(email);
      setStep('code');
    } catch {
      toast.error('Не получилось отправить код, попробуйте позже');
    }
  };

  // Возвращается без ошибки и при конфликте тоже: код верный, EmailCodeStep
  // не должен считать это неверным кодом и списывать попытку. Решение —
  // перенести или нет — принимается отдельно, в диалоге MergeConflictDialog.
  const verifyAndBind = async (bindEmail: string, code: string) => {
    try {
      const result = await confirmEmail.mutateAsync({ email: bindEmail, code });
      toast.success(mergeToastMessage(result.mergedFrom ?? EMPTY_TRANSFER));
      return result;
    } catch (err) {
      if (merge.catchConflict(err)) return undefined;
      throw err;
    }
  };

  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10">
            <IconMail className="h-5 w-5 text-primary" />
          </div>
          <div>
            <p className="text-sm font-medium">Почта</p>
            {connected ? (
              <Badge variant="secondary" className="gap-1 mt-0.5">
                <IconCheck className="h-3 w-3" />
                {externalId}
              </Badge>
            ) : (
              <p className="text-xs text-muted-foreground">Не подключена</p>
            )}
          </div>
        </div>

        {connected ? (
          <AlertDialog>
            <AlertDialogTrigger
              className={cn(buttonVariants({ variant: 'outline', size: 'sm' }))}
              disabled={!canUnbind || unbinding}
              title={!canUnbind ? 'Это единственный способ входа' : undefined}
            >
              {unbinding ? 'Отвязываем...' : 'Отвязать'}
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Отвязать почту?</AlertDialogTitle>
                <AlertDialogDescription>
                  Больше нельзя будет войти по этому адресу. Задачи и данные останутся — доступ через оставшийся способ входа не изменится.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Отмена</AlertDialogCancel>
                <AlertDialogAction onClick={onUnbind}>Отвязать</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        ) : step === 'idle' ? (
          <Button size="sm" onClick={() => setStep('email')}>Подключить</Button>
        ) : null}
      </div>

      {step === 'email' && (
        <form onSubmit={handleSubmitEmail} className="flex gap-2">
          <Input
            type="email"
            required
            autoFocus
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="почта@пример.ру"
            disabled={requestCode.isPending}
            className="h-10"
          />
          <Button type="submit" disabled={requestCode.isPending || !email} className="h-10 shrink-0">
            Продолжить
          </Button>
        </form>
      )}

      {step === 'code' && (
        <EmailCodeStep
          email={email}
          onBack={() => setStep('email')}
          onVerified={() => setStep('idle')}
          requestCode={(e) => requestCode.mutateAsync(e)}
          verifyCode={verifyAndBind}
        />
      )}

      <MergeConflictDialog
        conflict={merge.conflict}
        merging={merge.merging}
        onConfirm={merge.confirm}
        onCancel={merge.cancel}
      />
    </section>
  );
}
