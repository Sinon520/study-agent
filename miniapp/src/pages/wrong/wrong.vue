<template>
  <view class="sa-page">
    <!-- 筛选栏 -->
    <view class="sa-card">
      <view class="filter-row">
        <picker mode="selector" :range="subjectRange" @change="onSubjectChange">
          <view class="filter-item" :class="{ active: query.subject }">
            {{ query.subject || '全部科目' }}
          </view>
        </picker>

        <picker mode="selector" :range="masteryRange" @change="onMasteryChange">
          <view class="filter-item" :class="{ active: query.mastery }">
            {{ query.mastery || '全部掌握度' }}
          </view>
        </picker>

        <view class="filter-item" @tap="load">刷新</view>
      </view>

      <view class="search-row">
        <input
          v-model="query.keyword"
          class="search-input"
          placeholder="搜题干 / 答案 / 解析"
          confirm-type="search"
          @confirm="load"
        />
        <view class="search-btn" @tap="load">搜索</view>
      </view>

      <!-- 标签横向滚动 -->
      <scroll-view v-if="tags.length" scroll-x class="tag-scroll">
        <view class="tag-scroll-inner">
          <view
            v-for="tag in tags"
            :key="tag"
            class="tag-chip"
            :class="{ active: selectedTags.indexOf(tag) > -1 }"
            @tap="toggleTag(tag)"
          >
            {{ tag }}
          </view>
        </view>
      </scroll-view>
    </view>

    <!-- 批量操作条 -->
    <view v-if="selectedIds.length" class="batch-bar">
      <text>已选 {{ selectedIds.length }} 条</text>
      <text class="batch-action" @tap="batchMastery('已掌握')">标记已掌握</text>
      <text class="batch-action" @tap="clearSelection">取消</text>
    </view>

    <!-- 列表 -->
    <view v-if="loading" class="sa-empty">加载中…</view>
    <view v-else-if="!rows.length" class="sa-empty">没有符合条件的错题</view>

    <view v-else>
      <view
        v-for="row in rows"
        :key="row.id"
        class="sa-card item-card"
        :class="{ selected: selectedIds.indexOf(row.id) > -1 }"
        @longpress="toggleSelect(row.id)"
      >
        <view class="sa-between">
          <text class="sa-tag">{{ row.subject }}</text>
          <text class="sa-tag" :class="masteryClass(row.mastery)">{{ row.mastery }}</text>
        </view>

        <view class="item-content">{{ row.content }}</view>

        <view class="item-tags" v-if="row.tags && row.tags.length">
          <text v-for="t in row.tags" :key="t" class="sa-tag sa-tag-success">{{ t }}</text>
        </view>

        <view class="sa-between item-foot">
          <text class="sa-muted">{{ row.questionType || '未分类' }} · 难度 {{ row.difficulty }}</text>
          <text class="sa-muted">{{ formatTime(row.createTime) }}</text>
        </view>

        <view v-if="expandedId === row.id" class="item-detail">
          <view class="detail-block">
            <text class="detail-label">答案</text>
            <text class="detail-text">{{ row.answer || '-' }}</text>
          </view>
          <view class="detail-block">
            <text class="detail-label">解析</text>
            <text class="detail-text">{{ row.analysis || '-' }}</text>
          </view>
        </view>

        <view class="item-actions">
          <text class="link" @tap="toggleExpand(row.id)">
            {{ expandedId === row.id ? '收起' : '查看答案' }}
          </text>
          <text class="link" @tap="toggleSelect(row.id)">
            {{ selectedIds.indexOf(row.id) > -1 ? '取消选择' : '选择' }}
          </text>
        </view>
      </view>

      <view class="load-more" @tap="loadMore">
        {{ hasMore ? '加载更多' : '没有更多了' }}
      </view>
    </view>
  </view>
</template>

<script>
import { api } from '@/utils/request'

export default {
  data() {
    return {
      loading: true,
      rows: [],
      tags: [],
      selectedTags: [],
      expandedId: null,
      selectedIds: [],
      pageNum: 1,
      pageSize: 10,
      total: 0,
      subjectRange: ['全部科目', '英语', '数学', '政治', '专业课', '行测', '申论'],
      masteryRange: ['全部掌握度', '未掌握', '复习中', '已掌握'],
      query: { subject: '', mastery: '', keyword: '' },
    }
  },
  computed: {
    hasMore() {
      return this.rows.length < this.total
    },
  },
  onShow() {
    this.reload()
  },
  onPullDownRefresh() {
    this.reload().finally(() => uni.stopPullDownRefresh())
  },
  methods: {
    async reload() {
      this.pageNum = 1
      this.rows = []
      await this.load()
      this.loadTags()
    },
    async load() {
      this.loading = true
      try {
        const data = await api.wrongPage({
          pageNum: this.pageNum,
          pageSize: this.pageSize,
          subject: this.query.subject,
          mastery: this.query.mastery,
          keyword: this.query.keyword,
          tags: this.selectedTags,
        })
        const records = (data && data.records) || []
        this.rows = this.pageNum === 1 ? records : this.rows.concat(records)
        this.total = Number((data && data.total) || 0)
      } catch (e) {
        if (this.pageNum === 1) this.rows = []
      } finally {
        this.loading = false
      }
    },
    async loadMore() {
      if (!this.hasMore || this.loading) return
      this.pageNum += 1
      await this.load()
    },
    async loadTags() {
      try {
        this.tags = (await api.wrongTags()) || []
      } catch (e) {
        this.tags = []
      }
    },
    onSubjectChange(e) {
      const value = this.subjectRange[e.detail.value]
      this.query.subject = value === '全部科目' ? '' : value
      this.reload()
    },
    onMasteryChange(e) {
      const value = this.masteryRange[e.detail.value]
      this.query.mastery = value === '全部掌握度' ? '' : value
      this.reload()
    },
    toggleTag(tag) {
      const index = this.selectedTags.indexOf(tag)
      if (index > -1) {
        this.selectedTags.splice(index, 1)
      } else {
        this.selectedTags.push(tag)
      }
      this.reload()
    },
    toggleExpand(id) {
      this.expandedId = this.expandedId === id ? null : id
    },
    toggleSelect(id) {
      const index = this.selectedIds.indexOf(id)
      if (index > -1) {
        this.selectedIds.splice(index, 1)
      } else {
        this.selectedIds.push(id)
      }
    },
    clearSelection() {
      this.selectedIds = []
    },
    async batchMastery(mastery) {
      try {
        const count = await api.wrongBatchMastery(this.selectedIds, mastery)
        uni.showToast({ title: `已更新 ${count} 条`, icon: 'none' })
        this.clearSelection()
        await this.load()
      } catch (e) {
        // request 层已提示
      }
    },
    masteryClass(mastery) {
      if (mastery === '已掌握') return 'sa-tag-success'
      if (mastery === '复习中') return 'sa-tag-warn'
      return 'sa-tag-danger'
    },
    formatTime(value) {
      if (!value) return '-'
      return String(value).replace('T', ' ').slice(5, 16)
    },
  },
}
</script>

<style scoped>
.filter-row {
  display: flex;
}

.filter-item {
  flex: 1;
  margin-right: 12rpx;
  padding: 14rpx 0;
  text-align: center;
  font-size: 25rpx;
  color: #606266;
  background-color: #f5f7fa;
  border-radius: 10rpx;
}

.filter-item:last-child {
  margin-right: 0;
}

.filter-item.active {
  color: #409eff;
  background-color: #ecf5ff;
}

.search-row {
  display: flex;
  margin-top: 16rpx;
}

.search-input {
  flex: 1;
  height: 72rpx;
  padding: 0 20rpx;
  background-color: #f5f7fa;
  border-radius: 10rpx;
  font-size: 26rpx;
}

.search-btn {
  width: 120rpx;
  height: 72rpx;
  margin-left: 12rpx;
  line-height: 72rpx;
  text-align: center;
  font-size: 26rpx;
  color: #fff;
  background-color: #409eff;
  border-radius: 10rpx;
}

.tag-scroll {
  margin-top: 16rpx;
  white-space: nowrap;
}

.tag-scroll-inner {
  display: inline-flex;
}

.tag-chip {
  padding: 8rpx 20rpx;
  margin-right: 12rpx;
  font-size: 24rpx;
  color: #606266;
  background-color: #f5f7fa;
  border-radius: 24rpx;
}

.tag-chip.active {
  color: #fff;
  background-color: #409eff;
}

.batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 24rpx;
  margin-bottom: 16rpx;
  background-color: #ecf5ff;
  border-radius: 12rpx;
  font-size: 25rpx;
  color: #409eff;
}

.batch-action {
  margin-left: 20rpx;
  color: #409eff;
}

.item-card.selected {
  border: 2rpx solid #409eff;
}

.item-content {
  margin: 16rpx 0;
  font-size: 27rpx;
  line-height: 1.6;
  color: #303133;
}

.item-tags {
  margin-bottom: 12rpx;
}

.item-foot {
  margin-top: 8rpx;
}

.item-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid #f2f3f5;
}

.link {
  margin-left: 28rpx;
  font-size: 25rpx;
  color: #409eff;
}

.item-detail {
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: 1rpx dashed #ebeef5;
}

.detail-block {
  margin-bottom: 14rpx;
}

.detail-label {
  display: block;
  font-size: 23rpx;
  color: #909399;
  margin-bottom: 6rpx;
}

.detail-text {
  display: block;
  font-size: 26rpx;
  line-height: 1.7;
  color: #303133;
}

.load-more {
  padding: 30rpx 0;
  text-align: center;
  font-size: 25rpx;
  color: #909399;
}
</style>
