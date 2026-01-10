<template>
  <div class="chart-container">
    <Line
      :data="chartData"
      :options="chartOptions"
      aria-label="Sales trend chart showing revenue over time"
      role="img"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Line } from 'vue-chartjs'
import {
  Chart as ChartJS,
  LineElement,
  PointElement,
  LinearScale,
  CategoryScale,
  Title,
  Tooltip,
  Legend,
  Filler,
  type ChartOptions as ChartJSOptions,
} from 'chart.js'
import type { SalesAggregate } from '../api'

ChartJS.register(LineElement, PointElement, LinearScale, CategoryScale, Title, Tooltip, Legend, Filler)

const props = defineProps<{
  salesData: SalesAggregate[]
}>()

const chartData = computed(() => ({
  labels: props.salesData.map((d) => new Date(d.periodStart).toLocaleDateString()),
  datasets: [
    {
      label: 'Revenue',
      data: props.salesData.map((d) => d.totalAmount),
      borderColor: '#3b82f6',
      backgroundColor: 'rgba(59, 130, 246, 0.1)',
      tension: 0.3,
      fill: true,
    },
  ],
}))

const chartOptions: ChartJSOptions<'line'> = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: {
      display: true,
      position: 'top',
    },
    tooltip: {
      enabled: true,
      mode: 'index',
      intersect: false,
    },
  },
  scales: {
    y: {
      beginAtZero: true,
      ticks: {
        callback: (value) => `$${value.toLocaleString()}`,
      },
    },
  },
  interaction: {
    mode: 'nearest',
    axis: 'x',
    intersect: false,
  },
}
</script>

<style scoped>
.chart-container {
  @apply relative h-64 bg-white rounded-lg border border-neutral-200 p-4;
}
</style>
