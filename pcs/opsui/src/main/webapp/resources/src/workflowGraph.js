/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

const FINISHED = { FINISHED: true, SUCCESS: true, EXECUTIONCOMPLETE: true }
const FAILED = { FAILURE: true, RESULTSFAILURE: true, STOPPED: true }

export function instanceFinished(status) {
  return Boolean(FINISHED[String(status || '').toUpperCase()])
}

export function instanceFailed(status) {
  return Boolean(FAILED[String(status || '').toUpperCase()])
}

/**
 * Sequential workflow bubbles: a FINISHED run is all done and none
 * "current" (currentTaskId still names the last task). An in-flight
 * run marks tasks before the current one done, the current one
 * current, and the rest pending. A failed run marks the current task
 * failed.
 */
export function taskBubbleState(task, tasks, currentTaskId, status) {
  const finished = instanceFinished(status)
  const failed = instanceFailed(status)
  const list = tasks || []
  const idx = list.findIndex((item) => item && task && item.id === task.id)
  const currentIdx = list.findIndex((item) => item && item.id === currentTaskId)
  const isCurrent = Boolean(currentTaskId && task && task.id === currentTaskId)
  if (finished) {
    return { current: false, done: true, failed: false }
  }
  if (failed) {
    return {
      current: false,
      done: currentIdx >= 0 && idx >= 0 && idx < currentIdx,
      failed: isCurrent || (currentIdx < 0 && idx === list.length - 1)
    }
  }
  return {
    current: isCurrent,
    done: currentIdx >= 0 && idx >= 0 && idx < currentIdx,
    failed: false
  }
}
