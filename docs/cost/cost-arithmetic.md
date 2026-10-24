# Cost arithmetic (reproducible)

Run with any Python 3. Edit unit prices or usage assumptions and re-run; paste results into aws-cost-estimate.md.

```python
H=730
def lite(inst_rate, label):
    items=[
     ("EC2 "+label+" on-demand", f"{inst_rate} $/h x 730 h", inst_rate*H),
     ("Public IPv4 (in-use)", "0.005 $/h x 730", 0.005*H),
     ("EBS gp3 30 GB", "30 x 0.0912 $/GB-mo", 30*0.0912),
     ("EBS snapshots ~10 GB incremental", "10 x 0.05", 10*0.05),
     ("CloudFront data out (India) 20 GB", "20 x 0.109", 20*0.109),
     ("CloudFront HTTPS requests 0.5 M", "50 x 0.012 per 10k", 50*0.012),
     ("EC2 data out to internet 20 GB (upper bound; see note)", "20 x 0.1093", 20*0.1093),
     ("SQS long-poll + traffic ~3.3 M req", "25 queues x 3 polls/min x 43,800 min = 3.29 M x 0.40/M", 3.285*0.40),
     ("SNS publishes 0.1 M", "0.1 x 0.50/M", 0.1*0.50),
     ("SES 1,000 emails", "1000 x 0.00016", 1000*0.00016),
     ("S3 5 GB", "5 x 0.025", 5*0.025),
     ("CloudWatch Logs ingest 2 GB + store 2 GB", "2 x 0.67 + 2 x 0.03", 2*0.67+2*0.03),
     ("CloudWatch custom metrics 10", "10 x 0.30", 10*0.30),
     ("Secrets Manager 5 secrets", "5 x 0.40", 5*0.40),
     ("KMS 1 customer key", "1 x 1.00", 1.0),
     ("ECR 3.3 GB images", "3.3 x 0.10", 3.3*0.10),
    ]
    return items
for rate,label in [(0.0224,'t4g.medium (4 GB)'),(0.0448,'t4g.large (8 GB)')]:
    it=lite(rate,label); tot=sum(x[2] for x in it)
    print('LITE',label)
    for n,a,v in it: print(f'| {n} | {a} | {v:.2f} |')
    print(f'| **Total** | | **{tot:.2f}** |\n')
# full per hour
vcpu=2*(1+0.5+0.5+0.5+1+0.5+1+1+0.5+1+0.5+0.5); mem=2*(2+1+1+1+2+1+2+2+1+2+1+1)
print('vcpu',vcpu,'mem',mem)
def full(load):
    k=1.5 if load else 1.0
    items=[
     ("ALB hour", "0.0239", 0.0239),
     ("ALB LCU", "10 LCU x 0.008" if load else "1 LCU x 0.008", (10 if load else 1)*0.008),
     ("NAT gateway x2 (one per AZ)", "2 x 0.056", 2*0.056),
     ("NAT data processed", "2 GB x 0.056" if load else "0.2 GB x 0.056", (2 if load else 0.2)*0.056),
     ("Fargate ARM vCPU", f"{vcpu*k:g} vCPU x 0.02383", vcpu*k*0.02383),
     ("Fargate ARM memory", f"{mem*k:g} GB x 0.00261", mem*k*0.00261),
     ("RDS PostgreSQL db.m7g.large Multi-AZ", "0.479", 0.479),
     ("RDS gp3 Multi-AZ 50 GB (prorated)", "50 x 0.262 / 730", 50*0.262/730),
     ("ElastiCache cache.t4g.small Redis x2", "2 x 0.041", 2*0.041),
     ("WAF web ACL + 3 rules (prorated)", "(5 + 3 x 1) / 730", 8/730),
     ("CloudWatch Logs ingest", "1 GB x 0.67" if load else "0.1 GB x 0.67", (1 if load else 0.1)*0.67),
     ("CloudWatch custom metrics 200 (prorated)", "200 x 0.30 / 730", 200*0.30/730),
     ("X-Ray traces (5 % sampling of 1,000 req/s)", "180,000 x 0.000005" if load else "~0", 0.9 if load else 0.0),
     ("SQS requests incl. retries/DLQ", "2 M x 0.40/M" if load else "0.15 M x 0.40/M", (2 if load else 0.15)*0.40),
     ("SNS publishes", "0.2 M x 0.50/M" if load else "~0", 0.1 if load else 0.0),
     ("Inter-AZ transfer", "10 GB x 0.02 (0.01 each direction)" if load else "1 GB x 0.02", (10 if load else 1)*0.02),
     ("Public IPv4 (2 ALB + 2 NAT)", "4 x 0.005", 4*0.005),
     ("Load generators 2 x t4g.xlarge", "2 x 0.0896" if load else "0 (stopped)", 2*0.0896 if load else 0.0),
    ]
    return items
for load in (False, True):
    it=full(load); tot=sum(x[2] for x in it)
    print('FULL load' if load else 'FULL idle')
    for n,a,v in it: print(f'| {n} | {a} | {v:.3f} |')
    print(f'| **Total per hour** | | **{tot:.3f}** |\n')
    if load: L=tot
    else: I=tot
edge=[("CloudFront HTTPS requests 3.6 M/h (1,000 req/s)", "360 x 0.012 per 10k", 360*0.012),
      ("CloudFront data out 18 GB/h (5 KB avg)", "18 x 0.109", 18*0.109),
      ("WAF requests 3.6 M/h", "3.6 x 0.60/M", 3.6*0.60)]
E=sum(x[2] for x in edge)
for n,a,v in edge: print(f'| {n} | {a} | {v:.2f} |')
print('edge per hour',round(E,3))
run = 1.5*I + 4.5*L + 1*E
print(f'idle/h {I:.3f} load/h {L:.3f} edge/h {E:.3f}  RUN 6h = 1.5*{I:.3f} + 4.5*{L:.3f} + 1*{E:.3f} = {run:.2f}')
print('full always-on month (idle rate)', round(I*730,2))
```
