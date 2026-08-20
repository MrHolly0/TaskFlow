import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '/api/v1';

const getClient = () => {
  const token = localStorage.getItem('auth_token');
  return axios.create({
    baseURL: API_BASE,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
};

export type IdentityProvider = 'TELEGRAM' | 'EMAIL' | 'PHONE';

export interface Identity {
  provider: IdentityProvider;
  externalId: string;
  verifiedAt: string | null;
}

export interface AccountTransferResult {
  tasks: number;
  groups: number;
  tags: number;
  notifications: number;
  auditEvents: number;
  proposals: number;
  identities: number;
}

export interface IdentityBindResponse {
  identity: Identity;
  mergedFrom: AccountTransferResult | null;
}

export interface MergeConflictResponse {
  tasks: number;
  groups: number;
  tags: number;
  mergeToken: string;
}

// Владение идентификатором подтверждено кодом/подписью Telegram — это ещё
// не согласие на слияние учёток. Бэкенд поэтому не переносит данные сразу,
// а отвечает 409 с составом чужой учётки и токеном на 10 минут для /merge.
export const isMergeConflict = (err: unknown): err is { response: { status: 409; data: MergeConflictResponse } } => {
  return axios.isAxiosError(err) && err.response?.status === 409;
};

export const useIdentities = () => {
  return useQuery({
    queryKey: ['identities'],
    queryFn: async () => {
      const response = await getClient().get<Identity[]>('/identities');
      return response.data;
    },
    staleTime: 1000 * 60,
  });
};

export const useRequestBindEmailCode = () => {
  return useMutation({
    mutationFn: async (email: string) => {
      await getClient().post('/identities/email/request-code', { email });
    },
  });
};

export const useConfirmBindEmail = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ email, code }: { email: string; code: string }) => {
      const response = await getClient().post<IdentityBindResponse>('/identities/email/confirm', { email, code });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['identities'] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      queryClient.invalidateQueries({ queryKey: ['groups'] });
    },
  });
};

export interface RequestPhoneConfirmationResponse {
  confirmationNumber: string;
}

export interface PhoneBindConfirmationStatus {
  status: 'waiting' | 'confirmed' | 'conflict' | 'expired';
  identity: Identity | null;
  tasks: number | null;
  groups: number | null;
  tags: number | null;
  mergeToken: string | null;
}

export const useRequestBindPhoneConfirmation = () => {
  return useMutation({
    mutationFn: async (phone: string) => {
      const response = await getClient().post<RequestPhoneConfirmationResponse>(
        '/identities/phone/request-confirmation', { phone },
      );
      return response.data;
    },
  });
};

export const usePollBindPhoneConfirmation = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (phone: string) => {
      const response = await getClient().get<PhoneBindConfirmationStatus>(
        '/identities/phone/confirmation-status', { params: { phone } },
      );
      return response.data;
    },
    onSuccess: (data) => {
      if (data.status === 'confirmed') {
        queryClient.invalidateQueries({ queryKey: ['identities'] });
        queryClient.invalidateQueries({ queryKey: ['tasks'] });
        queryClient.invalidateQueries({ queryKey: ['groups'] });
      }
    },
  });
};

export const useCancelBindPhoneConfirmation = () => {
  return useMutation({
    mutationFn: async (phone: string) => {
      await getClient().post('/identities/phone/cancel-confirmation', { phone });
    },
  });
};

export const useBindTelegram = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (fields: Record<string, string>) => {
      const response = await getClient().post<IdentityBindResponse>('/identities/telegram', { fields });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['identities'] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      queryClient.invalidateQueries({ queryKey: ['groups'] });
    },
  });
};

export const useMergeAccounts = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (mergeToken: string) => {
      const response = await getClient().post<IdentityBindResponse>('/identities/merge', { token: mergeToken });
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['identities'] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      queryClient.invalidateQueries({ queryKey: ['groups'] });
    },
  });
};

export const useUnbindIdentity = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (provider: IdentityProvider) => {
      await getClient().delete(`/identities/${provider}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['identities'] });
    },
  });
};
