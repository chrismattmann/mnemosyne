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
const FAILED = { FAILURE: true, RESULTSFAILURE: true, STOPPED: true, ERROR: true }
const LIVE = {
  'PGE EXEC': true,
  CRAWLING: true,
  'BUILDING CONFIG FILE': true,
  'STAGING INPUT': true,
  EXECUTING: true,
  STARTED: true,
  PAUSED: true
}
const WAITING = { QUEUED: true, CREATED: true, RSUBMIT: true, LOADED: true, METMISS: true }

export function instanceFinished(status) {
  return Boolean(FINISHED[String(status || '').toUpperCase()])
}

export function instanceFailed(status) {
  return Boolean(FAILED[String(status || '').toUpperCase()])
}

export function instanceTerminal(status) {
  return instanceFinished(status) || instanceFailed(status)
}

export function instanceLive(status) {
  return Boolean(LIVE[String(status || '').toUpperCase()])
}

export function instanceWaiting(status) {
  return Boolean(WAITING[String(status || '').toUpperCase()])
}

/**
 * Ghost in the instance repo: the engine is not executing this id.
 * `running` comes from GET /workflow/instances (worker map). Missing
 * `running` means an older WM — do not guess.
 */
export function instanceAbandoned(inst) {
  if (!inst) {
    return false
  }
  if (inst.abandoned === true) {
    return true
  }
  if (inst.abandoned === false) {
    return false
  }
  if (parseEnd(inst.endDateTime) || instanceTerminal(inst.status)) {
    return false
  }
  return inst.running === false
}

function parseEnd(endIso) {
  if (!endIso) {
    return false
  }
  const end = Date.parse(endIso)
  return Number.isFinite(end)
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
