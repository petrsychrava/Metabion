<script lang="ts">
import { Line } from 'vue-chartjs'

// Register via options so test-utils can stub the anonymous vue-chartjs component by name.
export default {
  components: { Line },
}
</script>

<script setup lang="ts">
import { computed } from 'vue'
import { isDark } from '@/theme'
import {
  CategoryScale,
  Chart as ChartJS,
  Legend,
  LinearScale,
  LineElement,
  PointElement,
  Tooltip,
} from 'chart.js'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Legend, Tooltip)

const props = defineProps<{
  labels: string[]
  datasets: { label: string; data: (number | null)[] }[]
}>()

const chartData = computed(() => ({
  labels: props.labels,
  datasets: props.datasets.map((d, i) => ({
    label: d.label,
    data: d.data,
    borderColor: ['#2563eb', '#dc2626', '#059669'][i % 3],
    backgroundColor: 'transparent',
    spanGaps: true,
    tension: 0.2,
  })),
}))

const chartOptions = computed(() => {
  const text = isDark.value ? '#d1d5db' : '#4b5563'
  const grid = isDark.value ? '#374151' : '#e5e7eb'
  return {
    responsive: true,
    maintainAspectRatio: false,
    scales: {
      x: { ticks: { color: text }, grid: { color: grid } },
      y: { beginAtZero: true, ticks: { color: text }, grid: { color: grid } },
    },
    plugins: { legend: { labels: { color: text } } },
  }
})
</script>

<template>
  <div class="h-64">
    <Line :data="chartData" :options="chartOptions" />
  </div>
</template>
