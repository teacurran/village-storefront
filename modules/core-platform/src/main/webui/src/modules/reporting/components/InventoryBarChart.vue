<template>
  <div class="chart-container">
    <Bar
      :data="chartData"
      :options="chartOptions"
      aria-label="Inventory aging chart showing slowest moving items by days in stock"
      role="img"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Bar } from 'vue-chartjs'
import {
  Chart as ChartJS,
  BarElement,
  CategoryScale,
  LinearScale,
  Title,
  Tooltip,
  Legend,
  type ChartOptions as ChartJSOptions,
} from 'chart.js'

ChartJS.register(BarElement, CategoryScale, LinearScale, Title, Tooltip, Legend)

interface InventoryAgingItem {
  id: string
  variant?: { sku: string }
  location?: { name: string }
  quantity: number
  daysInStock: number
}

const props = defineProps<{
  inventoryData: InventoryAgingItem[]
}>()

const chartData = computed(() => {
  const top10 = props.inventoryData.slice(0, 10)
  return {
    labels: top10.map((item) => item.variant?.sku || 'Unknown'),
    datasets: [
      {
        label: 'Days in Stock',
        data: top10.map((item) => item.daysInStock),
        backgroundColor: 'rgba(239, 68, 68, 0.7)',
        borderColor: 'rgba(239, 68, 68, 1)',
        borderWidth: 1,
      },
    ],
  }
})

const chartOptions: ChartJSOptions<'bar'> = {
  indexAxis: 'y',
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: {
      display: true,
      position: 'top',
    },
    tooltip: {
      enabled: true,
      callbacks: {
        afterLabel: (context) => {
          const item = props.inventoryData[context.dataIndex]
          return `Quantity: ${item?.quantity || 0}`
        },
      },
    },
  },
  scales: {
    x: {
      beginAtZero: true,
      title: {
        display: true,
        text: 'Days in Stock',
      },
    },
    y: {
      title: {
        display: true,
        text: 'SKU',
      },
    },
  },
}
</script>

<style scoped>
.chart-container {
  @apply relative h-80 bg-white rounded-lg border border-neutral-200 p-4;
}
</style>
