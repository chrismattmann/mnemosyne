/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

/**
 * Load the resource screen without making it wait for the full health report.
 *
 * Resource Manager data is the screen's required request. Health is useful
 * supporting context, but it queries several services and can be slow during
 * a busy crawl or index. A failed or slow health refresh must not hide an
 * already available Resource Manager response behind a spinner.
 */
export async function loadResourceOverview(options) {
  const overview = await options.getResources()
  if (options.current()) {
    options.resource(overview)
  }

  runSecondary(options.getHealth, options.current, options.health)

  return overview
}

/** Run supporting data without delaying or failing the view's primary data. */
export function runSecondary(request, current, success, failure) {
  return Promise.resolve()
    .then(request)
    .then(body => {
      if (current()) {
        success(body)
      }
    })
    .catch(error => {
      if (current() && failure) {
        failure(error)
      }
    })
}
