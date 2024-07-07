# Hubitat App for Kidde HomeSafe Devices

# How to install
The easiest way to install and keep up to date is to use [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/installing.html). Search for Kidde in Integrations by tag to install - otherwise follow manual process below

This is an integration for Hubitat hubs to enable interfacing with Kidde HomeSafe devices. 

To use, go to your Hubitat hub, Go to Developer tools / Apps Code / Add New App and paste and save this file

https://raw.githubusercontent.com/schwark/hubitat-kidde/main/kidde.groovy

Go to Apps / Add User App / Kidde HomeSafe

Put in your Kidde username and password from the Kidde App

A Omni Sensor device will be created for each detector in your account using the names for each detector used in your Kidde app.