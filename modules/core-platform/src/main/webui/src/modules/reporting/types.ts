/**
 * SSE Event Types for Reporting
 */

export interface SSEReportJobEvent {
  jobId: string
  reportType: string
  status: 'pending' | 'running' | 'completed' | 'failed'
  completedAt?: string
  downloadUrl?: string
  errorMessage?: string
}
