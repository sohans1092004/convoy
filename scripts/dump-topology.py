"""Write infra/aws/shared/topology.json from the SNS/SQS layout the services create in LocalStack (P6.C).

The code is the source of truth: each service ensures its topics, queues, DLQs and filtered subscriptions at startup
under the local profile. With the full local stack up (every service started once), this reads that layout back,
and Terraform applies exactly it in AWS, where the services only check it (prod profile). A drift between the two
stops a service at startup with the missing piece named.

    docker compose up -d && python scripts/dump-topology.py
    COMPOSE_PROJECT_NAME=convoy-lite ... python scripts/dump-topology.py   (any stack with a localstack service)
"""
import json
import pathlib
import subprocess
import sys

OUT = pathlib.Path(__file__).resolve().parent.parent / 'infra' / 'aws' / 'shared' / 'topology.json'


def aws(*args):
    out = subprocess.run(['docker', 'compose', 'exec', '-T', 'localstack', 'awslocal', *args, '--output', 'json'],
                         check=True, capture_output=True, text=True).stdout
    return json.loads(out) if out.strip() else {}


def name(arn):
    return arn.rsplit(':', 1)[1]


def main():
    topics = sorted(name(t['TopicArn']) for t in aws('sns', 'list-topics').get('Topics', []))
    queues = []
    for url in sorted(aws('sqs', 'list-queues').get('QueueUrls', [])):
        q = url.rsplit('/', 1)[1]
        if '-dlq' in q:
            continue
        attrs = aws('sqs', 'get-queue-attributes', '--queue-url', url, '--attribute-names', 'All')['Attributes']
        queues.append({'name': q, 'arn': attrs['QueueArn'], 'fifo': q.endswith('.fifo'),
                       'dlq': name(json.loads(attrs['RedrivePolicy'])['deadLetterTargetArn']),
                       'visibility_timeout': int(attrs['VisibilityTimeout'])})
    by_arn = {q['arn']: q for q in queues}
    for t in topics:
        arn = f'arn:aws:sns:ap-south-1:000000000000:{t}'
        for s in aws('sns', 'list-subscriptions-by-topic', '--topic-arn', arn).get('Subscriptions', []):
            q = by_arn.get(s['Endpoint'])
            if q is None:
                continue
            a = aws('sns', 'get-subscription-attributes', '--subscription-arn', s['SubscriptionArn'])['Attributes']
            q['topic'] = t
            q['event_types'] = sorted(json.loads(a.get('FilterPolicy', '{}')).get('event_type', []))
    missing = [q['name'] for q in queues if 'topic' not in q]
    if missing:
        sys.exit(f'queues without a subscription: {missing}')
    for q in queues:
        del q['arn']
    OUT.write_text(json.dumps({'topics': topics, 'queues': queues}, indent=2) + '\n', encoding='utf-8')
    print(f'{len(topics)} topics, {len(queues)} queues (+{len(queues)} DLQs) -> {OUT}')


if __name__ == '__main__':
    main()
