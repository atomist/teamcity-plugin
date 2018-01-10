# Atomist Team City Plugin

This plugin sends notifications to Atomist on build start, build failure, build success, and build failure to start.

## Why would you do this?

Atomist is a development automation toolset for smoothing our work. The built-in Atomist automations bring information about commits, builds, GitHub statuses, k8s deployments (optional), and more together cleanly, with Slack messages that update themselves with new information and action buttons.

This plugin cases build status to show up in there.

You can also write your own automations to respond to this kind of build event. Then you can do things like automatically restart agent-related failures, instead of bugging people.

## To use this plugin:

### Build

Clone this repo and `mvn package` (you need maven installed)

This produces a zip file in the `target` directory.

### Install

In Team City, Administration (top right corner, if you're an admin) -> Plugins List (bottom of the left pane) -> Upload plugin zip

### Configure

In your Team City user settings (click your name in the top right) -> Notification Rules (left nav) -> Atomist (under the Notification Rules header).

For your Atomist Team ID, enter the Team that Atomist reports to you in Slack when you do `@atomist pwd`

For the Base URL of TeamCity, enter `http://teamcity.yourcompany.com` or whatever URL is at the beginning of the URL in your browser right now while you're configuring this notification.

I chose to watch all build configurations (<Root project>) and All branches.

note: I experienced some trickiness getting to "all branches"... I had to narrow the build configuration selection to a single one before it would let me change "Edit Branch Filter" (using the little wand to the right of the box) to `<all branches>`.

Send at least these events: Build fails (don't check the "only notify... boxes"); Build is successful; Build starts; and Build fails to start.

### Debug

Currently this plugin sends some output to stdout, so check TeamCity server's `catalina.out` to see what it's sending, or to look for exceptions.

Copyright 2018 Atomist