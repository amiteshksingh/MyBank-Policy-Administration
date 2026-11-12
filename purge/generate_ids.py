import random
import string

count = 2000
length = 10

chars = string.ascii_uppercase + string.digits

ids = set()

while len(ids) < count:
    ids.add(''.join(random.choices(chars, k=length)))

with open("uuids.txt", "w") as f:
    for uid in ids:
        f.write(uid + "\n")

print(f"Generated {len(ids)} IDs in uuids.txt")