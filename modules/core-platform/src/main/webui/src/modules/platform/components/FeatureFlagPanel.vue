<template>
  <div class="feature-flag-panel" data-test="feature-flag-panel">
    <div class="panel-header">
      <h3>
        <i class="pi pi-flag"></i>
        Feature Flag: {{ flag?.flagKey }}
      </h3>
      <button class="close-btn" data-test="close-panel" @click="$emit('close')">
        <i class="pi pi-times"></i>
      </button>
    </div>

    <div v-if="!flag" class="empty-state">
      <p>No feature flag selected</p>
    </div>

    <div v-else class="panel-content">
      <!-- Toggle and Basic Info -->
      <section class="info-section">
        <div class="toggle-row">
          <label class="toggle-label">
            <input
              type="checkbox"
              :checked="flag.enabled"
              data-test="flag-toggle"
              @change="handleToggle"
            />
            <span class="toggle-switch"></span>
            <span class="toggle-text">{{ flag.enabled ? 'Enabled' : 'Disabled' }}</span>
          </label>
          <span v-if="flag.stale" class="stale-badge" data-test="stale-indicator">
            <i class="pi pi-exclamation-triangle"></i>
            Needs Review
          </span>
        </div>

        <div v-if="flag.staleReason" class="stale-reason">
          <strong>Why stale:</strong> {{ flag.staleReason }}
        </div>
      </section>

      <!-- Governance Metadata -->
      <section class="metadata-section" data-test="governance-metadata">
        <h4>Governance Metadata</h4>

        <div class="metadata-grid">
          <div class="metadata-field">
            <label>Owner</label>
            <input
              v-if="editing"
              v-model="editForm.owner"
              type="email"
              data-test="edit-owner"
              placeholder="owner@example.com"
            />
            <p v-else>{{ flag.owner }}</p>
          </div>

          <div class="metadata-field">
            <label>Risk Level</label>
            <select
              v-if="editing"
              v-model="editForm.riskLevel"
              data-test="edit-risk-level"
            >
              <option value="LOW">Low</option>
              <option value="MEDIUM">Medium</option>
              <option value="HIGH">High</option>
              <option value="CRITICAL">Critical</option>
            </select>
            <p v-else>
              <span :class="['risk-badge', `risk-${flag.riskLevel.toLowerCase()}`]">
                {{ flag.riskLevel }}
              </span>
            </p>
          </div>

          <div class="metadata-field">
            <label>Review Cadence (Days)</label>
            <input
              v-if="editing"
              v-model.number="editForm.reviewCadenceDays"
              type="number"
              min="7"
              max="365"
              data-test="edit-review-cadence"
            />
            <p v-else>{{ flag.reviewCadenceDays || 'Not set' }}</p>
          </div>

          <div class="metadata-field">
            <label>Expiry Date</label>
            <input
              v-if="editing"
              v-model="editForm.expiryDate"
              type="date"
              data-test="edit-expiry-date"
            />
            <p v-else>{{ flag.expiryDate ? formatDate(flag.expiryDate) : 'None' }}</p>
          </div>

          <div class="metadata-field full-width">
            <label>Description</label>
            <textarea
              v-if="editing"
              v-model="editForm.description"
              rows="3"
              data-test="edit-description"
              placeholder="What does this flag control and why?"
            ></textarea>
            <p v-else>{{ flag.description || 'No description' }}</p>
          </div>

          <div class="metadata-field full-width">
            <label>Rollout Plan</label>
            <textarea
              v-if="editing"
              v-model="editForm.rolloutPlan"
              rows="3"
              data-test="edit-rollout-plan"
              placeholder="Timeline + tenant cohort strategy..."
            ></textarea>
            <p v-else>{{ flag.rolloutPlan || 'No rollout plan documented' }}</p>
          </div>

          <div class="metadata-field full-width">
            <label>
              Rollback Instructions
              <span v-if="isHighRisk" class="required-indicator">(Required for {{ flag.riskLevel }})</span>
            </label>
            <textarea
              v-if="editing"
              v-model="editForm.rollbackInstructions"
              rows="4"
              data-test="edit-rollback"
              placeholder="How to safely disable this flag in case of issues..."
            ></textarea>
            <div v-else class="rollback-display">
              <p v-if="flag.rollbackInstructions">{{ flag.rollbackInstructions }}</p>
              <div v-else-if="isHighRisk" class="missing-rollback-warning">
                <i class="pi pi-exclamation-circle"></i>
                Missing rollback instructions for {{ flag.riskLevel }} risk flag
              </div>
              <p v-else>No rollback instructions provided</p>
            </div>
          </div>
        </div>
      </section>

      <!-- Review Status -->
      <section class="review-section">
        <h4>Review Status</h4>
        <div class="review-info">
          <div class="review-field">
            <strong>Last Reviewed:</strong>
            <span>{{ flag.lastReviewedAt ? formatDate(flag.lastReviewedAt) : 'Never' }}</span>
          </div>
          <div class="review-field">
            <strong>Created:</strong>
            <span>{{ formatDate(flag.createdAt) }}</span>
          </div>
          <div class="review-field">
            <strong>Updated:</strong>
            <span>{{ formatDate(flag.updatedAt) }}</span>
          </div>
        </div>

        <button
          v-if="!editing"
          class="review-btn"
          data-test="mark-reviewed"
          @click="handleMarkReviewed"
        >
          <i class="pi pi-check"></i>
          Mark as Reviewed
        </button>
      </section>

      <!-- Actions -->
      <section class="actions-section">
        <div v-if="editing" class="edit-actions">
          <button class="btn-secondary" @click="cancelEdit">Cancel</button>
          <button class="btn-primary" data-test="save-changes" @click="saveChanges">
            <i class="pi pi-save"></i>
            Save Changes
          </button>
        </div>
        <div v-else class="view-actions">
          <button class="btn-secondary" data-test="edit-metadata" @click="startEdit">
            <i class="pi pi-pencil"></i>
            Edit Metadata
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import type { FeatureFlagDto, UpdateFeatureFlagRequest } from '../types'

/**
 * Feature Flag Panel Component
 *
 * Displays and allows editing of feature flag governance metadata.
 * Supports inline editing of owner, risk level, review cadence, expiry,
 * description, and rollback instructions.
 *
 * References:
 * - Task I5.T2: Platform admin console (feature flag management)
 * - docs/feature_flags/governance.md
 */

interface Props {
  flag: FeatureFlagDto | null
  loading?: boolean
}

interface Emits {
  (e: 'close'): void
  (e: 'update', flagId: string, request: UpdateFeatureFlagRequest): void
  (e: 'toggle', flagId: string, enabled: boolean, reason?: string): void
  (e: 'review', flagId: string, notes?: string): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const editing = ref(false)
const editForm = ref<UpdateFeatureFlagRequest>({})

const isHighRisk = computed(() => {
  const level = props.flag?.riskLevel
  return level === 'HIGH' || level === 'CRITICAL'
})

watch(
  () => props.flag,
  (newFlag) => {
    if (newFlag && editing.value) {
      resetEditForm()
    }
  }
)

function startEdit() {
  if (!props.flag) return
  editing.value = true
  resetEditForm()
}

function resetEditForm() {
  if (!props.flag) return
  editForm.value = {
    owner: props.flag.owner,
    riskLevel: props.flag.riskLevel,
    reviewCadenceDays: props.flag.reviewCadenceDays,
    expiryDate: props.flag.expiryDate,
    description: props.flag.description,
    rolloutPlan: props.flag.rolloutPlan,
    rollbackInstructions: props.flag.rollbackInstructions,
  }
}

function cancelEdit() {
  editing.value = false
  editForm.value = {}
}

function saveChanges() {
  if (!props.flag) return

  // Validate high-risk flags have rollback instructions
  if (isHighRisk.value && !editForm.value.rollbackInstructions?.trim()) {
    alert(`Rollback instructions are required for ${props.flag.riskLevel} risk flags`)
    return
  }

  emit('update', props.flag.id, editForm.value)
  editing.value = false
}

function handleToggle(event: Event) {
  if (!props.flag) return
  const target = event.target as HTMLInputElement
  const enabled = target.checked

  let reason: string | undefined
  if (!enabled) {
    const promptMessage =
      props.flag.riskLevel === 'CRITICAL'
        ? 'Disabling CRITICAL flag. Enter reason:'
        : 'Provide reason for disabling flag (required for audit):'
    reason = prompt(promptMessage) ?? ''
    if (!reason.trim()) {
      target.checked = !enabled
      return
    }
    reason = reason.trim()
  }

  emit('toggle', props.flag.id, enabled, reason)
}

function handleMarkReviewed() {
  if (!props.flag) return
  const reason = prompt('Enter review notes (optional):')
  emit('review', props.flag.id, reason?.trim())
}

function formatDate(dateString: string): string {
  const date = new Date(dateString)
  return date.toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}
</script>

<style scoped>
.feature-flag-panel {
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.25rem;
  background: #f9fafb;
  border-bottom: 1px solid #e5e7eb;
}

.panel-header h3 {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 1.125rem;
  font-weight: 600;
  margin: 0;
}

.close-btn {
  background: transparent;
  border: none;
  padding: 0.5rem;
  cursor: pointer;
  font-size: 1.25rem;
  color: #6b7280;
  transition: color 0.2s;
}

.close-btn:hover {
  color: #111;
}

.panel-content {
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

/* Toggle Section */
.info-section {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.toggle-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.toggle-label {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  cursor: pointer;
}

.toggle-label input[type='checkbox'] {
  position: absolute;
  opacity: 0;
}

.toggle-switch {
  position: relative;
  width: 48px;
  height: 24px;
  background: #d1d5db;
  border-radius: 12px;
  transition: background 0.2s;
}

.toggle-switch::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 20px;
  height: 20px;
  background: white;
  border-radius: 50%;
  transition: transform 0.2s;
}

.toggle-label input:checked + .toggle-switch {
  background: #22c55e;
}

.toggle-label input:checked + .toggle-switch::after {
  transform: translateX(24px);
}

.toggle-text {
  font-weight: 500;
  color: #374151;
}

.stale-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.375rem 0.75rem;
  background: #fef3c7;
  color: #92400e;
  border-radius: 6px;
  font-size: 0.875rem;
  font-weight: 500;
}

.stale-reason {
  padding: 0.75rem;
  background: #fffbeb;
  border-left: 3px solid #f59e0b;
  border-radius: 4px;
  font-size: 0.9rem;
}

/* Metadata Section */
.metadata-section h4,
.review-section h4 {
  font-size: 1rem;
  font-weight: 600;
  margin-bottom: 1rem;
  color: #374151;
}

.metadata-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
}

.metadata-field {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.metadata-field.full-width {
  grid-column: 1 / -1;
}

.metadata-field label {
  font-weight: 500;
  font-size: 0.875rem;
  color: #6b7280;
}

.required-indicator {
  color: #ef4444;
  font-size: 0.8rem;
}

.metadata-field input,
.metadata-field select,
.metadata-field textarea {
  padding: 0.625rem;
  border: 1px solid #d1d5db;
  border-radius: 4px;
  font-size: 0.95rem;
  font-family: inherit;
}

.metadata-field textarea {
  resize: vertical;
}

.metadata-field p {
  margin: 0;
  color: #374151;
}

.risk-badge {
  display: inline-block;
  padding: 0.25rem 0.75rem;
  border-radius: 12px;
  font-size: 0.875rem;
  font-weight: 500;
  text-transform: uppercase;
}

.risk-badge.risk-low {
  background: #d1fae5;
  color: #065f46;
}

.risk-badge.risk-medium {
  background: #fef3c7;
  color: #92400e;
}

.risk-badge.risk-high {
  background: #fed7aa;
  color: #9a3412;
}

.risk-badge.risk-critical {
  background: #fee2e2;
  color: #991b1b;
}

.rollback-display {
  padding: 0.75rem;
  background: #f9fafb;
  border-radius: 4px;
  font-size: 0.9rem;
}

.missing-rollback-warning {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 4px;
  color: #991b1b;
  font-size: 0.9rem;
}

/* Review Section */
.review-info {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-bottom: 1rem;
}

.review-field {
  display: flex;
  gap: 0.5rem;
  font-size: 0.9rem;
}

.review-field strong {
  color: #6b7280;
  min-width: 120px;
}

.review-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  background: #22c55e;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-weight: 500;
  transition: background 0.2s;
}

.review-btn:hover {
  background: #16a34a;
}

/* Actions */
.actions-section {
  border-top: 1px solid #e5e7eb;
  padding-top: 1rem;
}

.edit-actions,
.view-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
}

.btn-primary,
.btn-secondary {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.625rem 1.25rem;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-weight: 500;
  transition: all 0.2s;
}

.btn-primary {
  background: #6366f1;
  color: white;
}

.btn-primary:hover {
  background: #4f46e5;
}

.btn-secondary {
  background: #f3f4f6;
  color: #374151;
  border: 1px solid #d1d5db;
}

.btn-secondary:hover {
  background: #e5e7eb;
}

.empty-state {
  padding: 3rem;
  text-align: center;
  color: #9ca3af;
}
</style>
