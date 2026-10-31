# AWS permissions Terraform needs for infra/aws/shared

What `plan`, `apply` and `destroy` call, by service. Checked 2026-10-31 with `aws iam simulate-principal-policy` for
`role/managed/AccountFullAccessRole` with `aws:RequestedRegion = ap-southeast-2` (the organisation SCPs included):
**all 75 allowed**. The same simulation shows `iam:CreateOpenIDConnectProvider` denied by the SCP in every region,
and `s3:CreateBucket` denied outside ap-southeast-2. Everything is scoped to names starting `convoy-` where the API allows it.

| Service | Actions | Resources |
|---|---|---|
| STS | GetCallerIdentity | (account id for bucket names and ARNs) |
| SNS | CreateTopic, DeleteTopic, GetTopicAttributes, SetTopicAttributes, TagResource, UntagResource, ListTagsForResource, Subscribe, Unsubscribe, GetSubscriptionAttributes, SetSubscriptionAttributes | `arn:aws:sns:ap-southeast-2:<acct>:convoy-*` |
| SQS | CreateQueue, DeleteQueue, GetQueueAttributes, SetQueueAttributes, GetQueueUrl, TagQueue, UntagQueue, ListQueueTags | `arn:aws:sqs:ap-southeast-2:<acct>:convoy-*` |
| S3 | CreateBucket, DeleteBucket, ListBucket; Get/Put BucketTagging, BucketPublicAccessBlock, BucketOwnershipControls, EncryptionConfiguration, BucketPolicy (+ Delete), BucketVersioning, LifecycleConfiguration, BucketCORS; Get BucketAcl, BucketWebsite, BucketLogging, AccelerateConfiguration, BucketRequestPayment, ReplicationConfiguration, BucketObjectLockConfiguration (read on refresh) | `arn:aws:s3:::convoy-*` |
| CloudWatch | PutMetricAlarm, DeleteAlarms, DescribeAlarms, TagResource, ListTagsForResource | `arn:aws:cloudwatch:ap-southeast-2:<acct>:alarm:convoy-*` |
| IAM | CreateUser, GetUser, DeleteUser, TagUser, UntagUser, PutUserPolicy, GetUserPolicy, DeleteUserPolicy, ListUserPolicies, ListAttachedUserPolicies, ListGroupsForUser, CreateAccessKey, ListAccessKeys, DeleteAccessKey | `arn:aws:iam::<acct>:user/convoy-*` |
| Budgets | ModifyBudget (create, update, delete), ViewBudget, TagResource, ListTagsForResource | `arn:aws:budgets::<acct>:budget/convoy-*` |
| SES (only with `ses_domain`) | VerifyDomainIdentity, VerifyDomainDkim, GetIdentityVerificationAttributes, GetIdentityDkimAttributes, DeleteIdentity | the domain identity |

Not needed: anything in EC2, Organizations, IAM roles or OIDC providers, KMS (SSE-S3 and SSE-SQS use AWS-owned keys),
and no access to non-Convoy resources. A dedicated role for these could replace `AccountFullAccessRole` later; with
`aws login` as the only access path today, the plan review (0 changes or destroys outside `convoy-`) is the guard.
