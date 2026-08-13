import { isTouchDevice } from '@/lib/device';
import { useSettings, VoiceInputMode } from './useSettings';

export function useEffectiveVoiceMode(): VoiceInputMode {
  const { data } = useSettings();
  const mode = isTouchDevice() ? data?.voiceInputModeMobile : data?.voiceInputModeDesktop;
  return mode ?? 'SILENCE';
}
