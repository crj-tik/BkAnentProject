package com.bkanent.marketing.campaign;

import java.util.Map;

record CampaignTargetData(int targetIndex, String platform, long accountId, int accountRevision,
                          String externalAccountRef, String permissionCode, String argumentsHash,
                          Map<String, String> arguments) { }
