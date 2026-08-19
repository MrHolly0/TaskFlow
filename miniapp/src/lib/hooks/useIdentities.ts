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

export type IdentityProvider = 'TELEGRAM' | 'EMAIL';

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
