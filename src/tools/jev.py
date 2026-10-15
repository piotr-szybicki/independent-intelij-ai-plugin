import json
import os
from urllib.error import HTTPError
from urllib.request import Request, urlopen

API_URL = "https://api.typesafe.ai/v1/systemone"
API_KEY = "apikey_22099b556607e45041e2978cadbf50436e49_1713254d39e600992460e2d70bb47880da8b98c0b4249da6ce646f219cae2078"

# payload = {
#     "model": "jev-latest",
#     "state": {
#         "ticket": "I was charged twice and need the duplicate refunded today."
#     },
#     "questions": {
#         # A yes/no probability from 0.0 to 1.0
#         "is_billing": {
#             "type": "noul",
#             "instructions": "Is this ticket about billing?",
#         },

#         # Selects one of your fixed options
#         "intent": {
#             "type": "choice",
#             "instructions": "What is the customer's main request?",
#             "criteria": {
#                 "refund": "The customer wants money returned.",
#                 "technical_help": "The customer needs technical assistance.",
#                 "account_change": "The customer wants to change their account.",
#                 "other": "None of the above.",
#             },
#         },

#         # Rates the ticket on your ordered scale
#         "urgency": {
#             "type": "score",
#             "instructions": "How urgent is this request?",
#             "criteria": ["can wait", "this week", "today"],
#         },
#     },
# }

payload = {
    "model": "jev-latest",
    "state": {
        "state": "I'm a 41 software engeener. I am very smart. I have some traiding experient. I work for a few large banks."
    },
    "questions": {
        # A yes/no probability from 0.0 to 1.0
        "traiding": {
            "type": "noul",
            "instructions": "Should I invest my own money into stocks?",
        },
    }
}

request = Request(
    API_URL,
    data=json.dumps(payload).encode("utf-8"),
    headers={
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json",
    },
    method="POST",
)

try:
    with urlopen(request, timeout=20) as response:
        result = json.load(response)
except HTTPError as error:
    print(error.status, error.read().decode("utf-8"))
    raise

print(result)