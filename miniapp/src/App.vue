<script>
export default {
  onLaunch() {
    console.log('[study-agent] 小程序启动')
  },
}
</script>

<style>
/* 全局样式：小程序端不支持 * 选择器与复杂 CSS，只做最基础的重置 */
page {
  background-color: #f5f7fa;
  font-size: 28rpx;
  color: #303133;
  font-family: -apple-system, 'PingFang SC', 'Helvetica Neue', Helvetica, Arial, sans-serif;
}

.sa-page {
  padding: 24rpx;
}

.sa-card {
  background-color: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 20rpx;
}

.sa-row {
  display: flex;
  align-items: center;
}

.sa-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sa-title {
  font-size: 34rpx;
  font-weight: 600;
}

.sa-sub {
  font-size: 24rpx;
  color: #909399;
}

.sa-muted {
  font-size: 24rpx;
  color: #909399;
}

.sa-primary {
  color: #409eff;
}

.sa-danger {
  color: #f56c6c;
}

.sa-tag {
  display: inline-block;
  padding: 2rpx 12rpx;
  border-radius: 20rpx;
  font-size: 22rpx;
  background-color: #ecf5ff;
  color: #409eff;
  margin-right: 8rpx;
}

.sa-tag-warn {
  background-color: #fdf6ec;
  color: #e6a23c;
}

.sa-tag-success {
  background-color: #f0f9eb;
  color: #67c23a;
}

.sa-tag-danger {
  background-color: #fef0f0;
  color: #f56c6c;
}

.sa-btn {
  height: 84rpx;
  line-height: 84rpx;
  border-radius: 12rpx;
  font-size: 30rpx;
  text-align: center;
  background-color: #409eff;
  color: #fff;
}

.sa-btn-plain {
  background-color: #fff;
  color: #409eff;
  border: 1rpx solid #409eff;
}

.sa-empty {
  padding: 100rpx 0;
  text-align: center;
  color: #909399;
  font-size: 26rpx;
}
</style>
