<template>
  <div class="login-view">
    <div class="login-card">
      <h1 class="text-2xl font-bold text-center mb-6">Village Storefront Admin</h1>

      <form @submit.prevent="handleLogin">
        <BaseInput
          v-model="email"
          type="email"
          label="Email"
          placeholder="admin@example.com"
          required
          class="mb-4"
        />

        <BaseInput
          v-model="password"
          type="password"
          label="Password"
          required
          class="mb-6"
        />

        <BaseButton
          type="submit"
          variant="primary"
          :loading="loading"
          full-width
        >
          Sign In
        </BaseButton>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseButton from '@/components/base/BaseButton.vue'

const router = useRouter()
const authStore = useAuthStore()

const email = ref('admin@example.com')
const password = ref('password')
const loading = ref(false)

async function handleLogin() {
  loading.value = true
  try {
    await authStore.login(email.value, password.value)
    router.push('/')
  } catch (error) {
    console.error('Login failed:', error)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-view {
  @apply min-h-screen bg-neutral-100 flex items-center justify-center px-4;
}

.login-card {
  @apply bg-white rounded-lg shadow-medium p-8 w-full max-w-md;
}
</style>
