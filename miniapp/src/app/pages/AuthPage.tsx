import { useState } from 'react';
import { IconBrandTelegram, IconSparkles, IconBolt, IconShield } from '@tabler/icons-react';
import { motion } from 'motion/react';
import { useStore } from '@/lib/store';
import {
  authenticateViaInitData,
  authenticateAsDemoUser,
  authenticateViaLoginWidget,
  isTelegramWebApp,
  getStoredToken,
  getUserFromToken,
  requestEmailCode,
} from '@/lib/auth';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { Separator } from '@/app/components/ui/separator';
import { EmailCodeStep } from '@/app/components/EmailCodeStep';
import { MuninLogo } from '@/app/components/MuninLogo';
import { TelegramLoginButton } from '@/app/components/TelegramLoginButton';
import { useAuthMethods } from '@/lib/hooks/useAuthMethods';

const features = [
  { icon: IconSparkles, title: 'Фокус-режим', desc: '1–3 задачи. Только самое важное.' },
  { icon: IconBolt, title: 'Голосовой ввод', desc: 'Надиктуй задачу — разберем сами.' },
  { icon: IconShield, title: 'Без перегруза', desc: 'Ассистент решает приоритеты за тебя.' },
];

export function AuthPage() {
  const setAuthenticated = useStore((s) => s.setAuthenticated);
  const login = useStore((s) => s.login);
  const [step, setStep] = useState<'main' | 'code'>('main');
  const [email, setEmail] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { data: authMethods } = useAuthMethods();
  // Пока ответ не пришёл, data === undefined — кнопка остаётся скрытой,
  // так же как при ошибке запроса: неизвестное состояние не рисуем как
  // разрешающее.
  const showTelegram = authMethods?.telegram === true;

  const applyAuth = () => {
    const token = getStoredToken();
    if (token) {
      const info = getUserFromToken(token);
      if (info) login({ id: info.id, name: info.username, username: info.username });
    }
    setAuthenticated(true);
  };

  const handleTelegramLogin = async () => {
    setLoading(true);
    setError(null);
    try {
      await authenticateViaInitData();
      applyAuth();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Ошибка авторизации';
      setError(message);
      console.error('Auth error:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleDemoLogin = async () => {
    setLoading(true);
    setError(null);
    try {
      await authenticateAsDemoUser();
      applyAuth();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Ошибка авторизации';
      setError(message);
      console.error('Demo auth error:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleWidgetAuth = async (fields: Record<string, string>) => {
    setLoading(true);
    setError(null);
    try {
      await authenticateViaLoginWidget(fields);
      applyAuth();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Ошибка авторизации через Telegram';
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  const handleEmailSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (loading) return;
    setLoading(true);
    setError(null);
    try {
      await requestEmailCode(email);
      setStep('code');
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Не получилось отправить код, попробуйте позже';
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-background flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        <motion.div
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5 }}
          className="space-y-10"
        >
          <div className="text-center space-y-4">
            <MuninLogo variant="auth" className="mx-auto h-auto w-44 text-foreground" />
            <p className="text-muted-foreground text-base">
              Планировщик для мозга, который не любит скучать
            </p>
          </div>

          {/* Преимущества — только на первом шаге: на вводе кода человек
              уже принял решение, и лишний текст ему мешает. */}
          {step === 'main' && (
            <motion.div
              initial={{ opacity: 0, y: 16 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.15, duration: 0.4 }}
              className="space-y-2"
            >
              {features.map((f) => {
                const Icon = f.icon;
                return (
                  <div key={f.title} className="flex items-center gap-3 rounded-xl bg-muted/50 p-3">
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10">
                      <Icon className="h-5 w-5 text-primary" />
                    </div>
                    <div className="min-w-0">
                      <div className="text-sm font-medium">{f.title}</div>
                      <div className="text-sm text-muted-foreground">{f.desc}</div>
                    </div>
                  </div>
                );
              })}
            </motion.div>
          )}

          {/* CTA */}
          <motion.div
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.3, duration: 0.4 }}
          >
            {step === 'code' ? (
              <EmailCodeStep
                email={email}
                onBack={() => {
                  setStep('main');
                  setError(null);
                }}
                onVerified={applyAuth}
              />
            ) : (
              <div className="space-y-5">
                <form onSubmit={handleEmailSubmit} className="flex gap-2">
                  <Input
                    type="email"
                    required
                    autoFocus
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="почта@пример.ру"
                    disabled={loading}
                    className="h-11"
                  />
                  <Button type="submit" disabled={loading || !email} className="h-11 shrink-0">
                    Продолжить
                  </Button>
                </form>

                {showTelegram && (
                  <>
                    <div className="flex items-center gap-3">
                      <Separator className="flex-1" />
                      <span className="text-xs text-muted-foreground shrink-0">или войти через</span>
                      <Separator className="flex-1" />
                    </div>

                    <div className="flex flex-wrap gap-3 justify-center">
                      {isTelegramWebApp() ? (
                        <button
                          onClick={handleTelegramLogin}
                          disabled={loading}
                          className="flex items-center justify-center gap-3 py-3 px-6 rounded-2xl font-semibold text-white transition-all active:scale-95 cursor-pointer disabled:opacity-70 disabled:cursor-not-allowed"
                          style={{ backgroundColor: '#0088cc' }}
                        >
                          <IconBrandTelegram className="w-5 h-5" />
                          Telegram
                        </button>
                      ) : (
                        <TelegramLoginButton onAuth={handleWidgetAuth} />
                      )}
                    </div>
                  </>
                )}

                {import.meta.env.DEV && !isTelegramWebApp() && (
                  <button
                    onClick={handleDemoLogin}
                    disabled={loading}
                    className="w-full flex items-center justify-center gap-3 py-3 px-6 rounded-2xl font-semibold transition-all active:scale-95 cursor-pointer disabled:opacity-70 disabled:cursor-not-allowed border border-border bg-muted/50 text-foreground text-sm"
                  >
                    Попробовать демо
                  </button>
                )}

                {error && (
                  <p className="text-center text-xs text-red-500 px-4">
                    {error}
                  </p>
                )}

                <p className="text-center text-xs text-muted-foreground px-4">
                  Входя, вы соглашаетесь с обработкой данных, нужных для работы сервиса.
                </p>
              </div>
            )}
          </motion.div>
        </motion.div>
      </div>
    </div>
  );
}
