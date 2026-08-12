import { useMutation, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '/api/v1';

const getClient = () => {
  const token = localStorage.getItem('auth_token');
  const client = axios.create({
    baseURL: API_BASE,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  client.interceptors.response.use(
    (r) => r,
    (err) => {
      if (err.response?.status === 401 || err.response?.status === 403) {
        localStorage.removeItem('auth_token');
        localStorage.removeItem('refresh_token');
        window.location.reload();
      }
      return Promise.reject(err);
    }
  );
  return client;
};

export type ProposalStatus = 'PENDING' | 'APPLIED' | 'PARTIALLY_APPLIED' | 'REJECTED' | 'EXPIRED' | 'FAILED';

export interface ProposedAction {
  ordinal: number;
  type: string;
  targetTaskId?: string;
  payload: Record<string, unknown>;
  summary: string;
  accepted: boolean;
}

export interface Proposal {
  id: string | null;
  shortCode: string | null;
  userId: string;
  status: ProposalStatus;
  sourceText: string;
  clarification?: string;
  actions: ProposedAction[];
  createdAt: string;
  expiresAt: string;
}

export interface ActionOutcome {
  ordinal: number;
  summary: string;
  success: boolean;
  error?: string;
}

export interface ApplyResult {
  status: ProposalStatus;
  appliedCount: number;
  totalCount: number;
  outcomes: ActionOutcome[];
}

export const useSendAssistantMessage = () => {
  return useMutation({
    mutationFn: async ({ text, file }: { text?: string; file?: File }) => {
      const formData = new FormData();
      if (text) formData.append('text', text);
      if (file) formData.append('file', file);
      const response = await getClient().post<Proposal>('/assistant/messages', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      return response.data;
    },
  });
};

export const useSetActionAccepted = () => {
  return useMutation({
    mutationFn: async ({ proposalId, ordinal, accepted }: { proposalId: string; ordinal: number; accepted: boolean }) => {
      const response = await getClient().patch<Proposal>(
        `/assistant/proposals/${proposalId}/actions/${ordinal}`,
        { accepted }
      );
      return response.data;
    },
  });
};

export const useApplyProposal = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (proposalId: string) => {
      const response = await getClient().post<ApplyResult>(`/assistant/proposals/${proposalId}/apply`);
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

export const useRejectProposal = () => {
  return useMutation({
    mutationFn: async (proposalId: string) => {
      await getClient().post(`/assistant/proposals/${proposalId}/reject`);
    },
  });
};
