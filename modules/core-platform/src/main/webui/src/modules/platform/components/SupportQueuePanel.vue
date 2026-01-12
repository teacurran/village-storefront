<template>
  <section class="support-queue-panel" data-test="support-queue-panel">
    <header class="panel-header">
      <div>
        <h2>Support Queue</h2>
        <p class="subtitle">Live tickets requiring platform intervention</p>
      </div>
      <div class="badge-group">
        <span class="badge" data-test="support-total">Open: {{ stats.totalOpen }}</span>
        <span class="badge badge-danger" data-test="support-urgent">
          Urgent: {{ stats.urgentCount }}
        </span>
        <span class="badge badge-muted" data-test="support-awaiting">
          Awaiting Reply: {{ stats.awaitingReplyCount }}
        </span>
      </div>
    </header>

    <div v-if="loading" class="state" data-test="support-loading">
      <i class="pi pi-spin pi-spinner" /> Loading support tickets...
    </div>
    <div v-else-if="error" class="state error" data-test="support-error">
      <i class="pi pi-exclamation-triangle" />
      {{ error }}
    </div>
    <div v-else-if="tickets.length === 0" class="state empty" data-test="support-empty">
      <i class="pi pi-check-circle" />
      All clear — no tickets need attention.
    </div>
    <ul v-else class="ticket-list">
      <li
        v-for="ticket in tickets"
        :key="ticket.id"
        class="ticket-card"
        data-test="support-ticket"
      >
        <div class="ticket-header">
          <span :class="['priority', ticket.priority]" data-test="ticket-priority">
            {{ formatPriority(ticket.priority) }}
          </span>
          <span class="ticket-ref" data-test="ticket-reference">#{{ ticket.reference }}</span>
          <span
            class="sla"
            :class="{ overdue: ticket.slaMinutesRemaining < 0 }"
            data-test="ticket-sla"
          >
            {{ formatSla(ticket.slaMinutesRemaining) }}
          </span>
        </div>
        <p class="ticket-subject" data-test="ticket-subject">{{ ticket.subject }}</p>
        <div class="ticket-meta">
          <span class="tenant" data-test="ticket-tenant">{{ ticket.tenantName }}</span>
          <span class="status" :class="ticket.status" data-test="ticket-status">
            {{ formatStatus(ticket.status) }}
          </span>
          <span class="channel" data-test="ticket-channel">
            <i class="pi pi-comments" />
            {{ ticket.channel }}
          </span>
          <span class="updated" data-test="ticket-updated">
            Updated {{ formatRelativeTime(ticket.updatedAt) }}
          </span>
        </div>
        <div class="ticket-actions">
          <button class="btn-secondary" @click="$emit('view-ticket', ticket)">
            <i class="pi pi-external-link" />
            View Ticket
          </button>
          <button class="btn-primary" data-test="ticket-impersonate" @click="$emit('impersonate', ticket)">
            <i class="pi pi-user-edit" />
            Impersonate
          </button>
        </div>
      </li>
    </ul>
  </section>
</template>

<script setup lang="ts">
import type { SupportQueueSnapshot, SupportTicketSummary } from '../types'

interface Props {
  tickets: SupportTicketSummary[]
  stats: Pick<SupportQueueSnapshot, 'totalOpen' | 'urgentCount' | 'awaitingReplyCount'>
  loading?: boolean
  error?: string | null
}

defineProps<Props>()
defineEmits<{
  (e: 'view-ticket', ticket: SupportTicketSummary): void
  (e: 'impersonate', ticket: SupportTicketSummary): void
}>()

function formatPriority(priority: SupportTicketSummary['priority']): string {
  switch (priority) {
    case 'urgent':
      return 'Critical'
    case 'high':
      return 'High'
    case 'normal':
      return 'Normal'
    default:
      return 'Low'
  }
}

function formatStatus(status: SupportTicketSummary['status']): string {
  switch (status) {
    case 'waiting_on_customer':
      return 'Waiting on customer'
    case 'pending':
      return 'Pending'
    case 'resolved':
      return 'Resolved'
    default:
      return 'Open'
  }
}

function formatSla(minutesRemaining: number): string {
  const absMinutes = Math.abs(minutesRemaining)
  const hours = Math.floor(absMinutes / 60)
  const minutes = absMinutes % 60
  const formatted = `${hours > 0 ? `${hours}h ` : ''}${minutes}m`
  return minutesRemaining < 0 ? `Overdue by ${formatted}` : `SLA in ${formatted}`
}

function formatRelativeTime(timestamp: string): string {
  const updated = new Date(timestamp)
  const now = new Date()
  const diffMs = now.getTime() - updated.getTime()
  const diffMinutes = Math.floor(diffMs / (1000 * 60))
  if (diffMinutes < 1) return 'just now'
  if (diffMinutes < 60) return `${diffMinutes}m ago`
  const diffHours = Math.floor(diffMinutes / 60)
  if (diffHours < 24) return `${diffHours}h ago`
  const diffDays = Math.floor(diffHours / 24)
  if (diffDays === 1) return 'yesterday'
  return `${diffDays}d ago`
}
</script>

<style scoped>
.support-queue-panel {
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
}

.panel-header h2 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.subtitle {
  margin: 0.25rem 0 0;
  color: #6b7280;
  font-size: 0.9rem;
}

.badge-group {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.badge {
  padding: 0.25rem 0.75rem;
  border-radius: 999px;
  background: #f3f4f6;
  color: #374151;
  font-size: 0.85rem;
  font-weight: 600;
}

.badge-danger {
  background: #fee2e2;
  color: #b91c1c;
}

.badge-muted {
  background: #e0f2fe;
  color: #0369a1;
}

.state {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 1rem;
  border-radius: 6px;
  background: #f9fafb;
  color: #4b5563;
}

.state.error {
  background: #fef2f2;
  color: #991b1b;
}

.state.empty {
  background: #f0fdf4;
  color: #166534;
}

.ticket-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.ticket-card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.ticket-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.priority {
  padding: 0.25rem 0.6rem;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
}

.priority.urgent {
  background: #fee2e2;
  color: #b91c1c;
}

.priority.high {
  background: #fef3c7;
  color: #92400e;
}

.priority.normal {
  background: #e0f2fe;
  color: #0369a1;
}

.priority.low {
  background: #ede9fe;
  color: #5b21b6;
}

.ticket-ref {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 0.85rem;
  color: #6b7280;
}

.sla {
  margin-left: auto;
  font-size: 0.85rem;
  font-weight: 600;
  color: #2563eb;
}

.sla.overdue {
  color: #dc2626;
}

.ticket-subject {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
  color: #111827;
}

.ticket-meta {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
  font-size: 0.85rem;
  color: #6b7280;
}

.ticket-meta .tenant {
  font-weight: 600;
  color: #374151;
}

.ticket-meta .status {
  text-transform: capitalize;
}

.ticket-actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.btn-secondary,
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.45rem 0.9rem;
  border-radius: 6px;
  border: none;
  cursor: pointer;
  font-weight: 500;
}

.btn-secondary {
  background: #f3f4f6;
  color: #374151;
  border: 1px solid #d1d5db;
}

.btn-primary {
  background: #4f46e5;
  color: #fff;
}

.btn-primary:hover {
  background: #4338ca;
}
</style>
