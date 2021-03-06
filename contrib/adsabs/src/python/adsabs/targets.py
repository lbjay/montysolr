'''
Created on Feb 4, 2011

@author: rca
'''

from montysolr.utils import MontySolrTarget, make_targets
from adsabs import api_calls
from montysolr import config
from java.util import HashMap
import time


def parse_human_name(message):
    input = unicode(message.getParam("input")).encode('utf8')
    (wid, results) = api_calls.dispatch("parse_human_name", input)
    if results:
        out = HashMap() #.of_(String, JArray_int)
        for k,v in results.items():
            out.put(k, v)
        message.setResults(out)



def montysolr_targets():
    targets = make_targets(
           '*:parse_human_name', parse_human_name,
           )
    
    return targets
    
